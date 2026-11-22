package org.fogofwarlabs.sc4j.scr.game.sprite.script.instruction;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.animation.AnimationEffects;
import org.fogofwarlabs.sc4j.engine.animation.AnimationTarget;
import org.fogofwarlabs.sc4j.engine.content.type.ImageType;
import org.fogofwarlabs.sc4j.engine.content.type.SoundType;
import org.fogofwarlabs.sc4j.engine.entity.Image;
import org.fogofwarlabs.sc4j.scr.game.sprite.Sprite;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecution;
import org.fogofwarlabs.sc4j.scr.game.sprite.script.IscriptExecutor;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Gives the iscript instructions of one game the image they run on: the image, its sprite, the unit context and the
 * bounded invocation of the current update, and through them the game.
 * <p>
 * The interpreter enters the image of each update before it runs and leaves it afterwards ({@link #enter},
 * {@link #leave}). An instruction may start a nested update (an image it creates runs its Init script at once), which
 * enters and leaves its own image, so the entries form a stack and an instruction reads its image before it starts
 * nested work. The engine effect requests ({@link AnimationEffects}) are not part of the iscript: its programs hold no
 * engine effect instruction, and a request fails.
 */
public final class ScrImageTarget implements AnimationTarget, AnimationEffects {
    /**
     * The launch offset of a weapon fire that uses the weapon's own offset.
     */
    private static final int OWN_FORWARD_OFFSET = -1;

    /**
     * The number of nested updates the target holds before it grows.
     */
    private static final int INITIAL_DEPTH = 8;

    private final @NotNull Game game;

    private @Nullable SpriteImage image;
    private @Nullable Sprite sprite;
    private @Nullable Unit unit;
    private @Nullable IscriptExecution execution;

    private @Nullable SpriteImage @NotNull [] savedImages;
    private @Nullable Sprite @NotNull [] savedSprites;
    private @Nullable Unit @NotNull [] savedUnits;
    private @Nullable IscriptExecution @NotNull [] savedExecutions;
    private int depth;

    /**
     * Creates a {@link ScrImageTarget} instance for one game.
     *
     * @param game the game whose images the instructions run on
     */
    public ScrImageTarget(@NotNull Game game) {
        this.game = game;

        this.image     = null;
        this.sprite    = null;
        this.unit      = null;
        this.execution = null;

        this.savedImages     = new SpriteImage[INITIAL_DEPTH];
        this.savedSprites    = new Sprite[INITIAL_DEPTH];
        this.savedUnits      = new Unit[INITIAL_DEPTH];
        this.savedExecutions = new IscriptExecution[INITIAL_DEPTH];
        this.depth           = 0;
    }

    /**
     * @return the game whose images the instructions run on
     */
    public @NotNull Game getGame() {
        return this.game;
    }

    /**
     * @return the iscript interpreter of the game
     */
    public @NotNull IscriptExecutor getExecutor() {
        return this.game.getIscriptExecutor();
    }

    /**
     * Returns the image of the current update.
     *
     * @return the image
     * @throws IllegalStateException if no update runs
     */
    public @NotNull SpriteImage getImage() {
        SpriteImage current = this.image;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No iscript update runs.");
        }

        return current;
    }

    /**
     * Returns the sprite of the image of the current update.
     *
     * @return the sprite
     * @throws IllegalStateException if no update runs
     */
    public @NotNull Sprite getSprite() {
        Sprite current = this.sprite;

        if (current == null) {
            throw Exceptions.newIllegalStateException("No iscript update runs.");
        }

        return current;
    }

    /**
     * @return the unit context of the current update, or {@code null} for a unit-free animation or when no update runs
     */
    public @Nullable Unit getUnit() {
        return this.unit;
    }

    /**
     * @return the bounded invocation of the current update, or {@code null} for an unbounded update or when no update runs
     */
    public @Nullable IscriptExecution getExecution() {
        return this.execution;
    }


    /**
     * Enters the image of an update; the image stays current until the matching {@link #leave}, which makes the image of the
     * enclosing update current again.
     *
     * @param execution the bounded invocation, or {@code null} for an unbounded update
     * @param image     the image
     * @param sprite    the sprite the image belongs to
     * @param unit      the unit context, or {@code null}
     */
    public void enter(@Nullable IscriptExecution execution, @NotNull SpriteImage image, @NotNull Sprite sprite, @Nullable Unit unit) {
        int level = this.depth;

        if (level == this.savedImages.length) {
            int length = level * 2;

            this.savedImages     = Arrays.copyOf(this.savedImages, length);
            this.savedSprites    = Arrays.copyOf(this.savedSprites, length);
            this.savedUnits      = Arrays.copyOf(this.savedUnits, length);
            this.savedExecutions = Arrays.copyOf(this.savedExecutions, length);
        }

        this.savedImages[level]     = this.image;
        this.savedSprites[level]    = this.sprite;
        this.savedUnits[level]      = this.unit;
        this.savedExecutions[level] = this.execution;

        this.depth = level + 1;

        this.image     = image;
        this.sprite    = sprite;
        this.unit      = unit;
        this.execution = execution;
    }

    /**
     * Leaves the image of the update entered last, making the image of the enclosing update current again.
     *
     * @throws IllegalStateException if no update runs
     */
    public void leave() {
        if (this.depth == 0) {
            throw Exceptions.newIllegalStateException("No iscript update runs.");
        }

        int level = --this.depth;

        this.image     = this.savedImages[level];
        this.sprite    = this.savedSprites[level];
        this.unit      = this.savedUnits[level];
        this.execution = this.savedExecutions[level];

        this.savedImages[level]     = null;
        this.savedSprites[level]    = null;
        this.savedUnits[level]      = null;
        this.savedExecutions[level] = null;
    }

    /**
     * Shows a frame on an animated image.
     * <p>
     * Sets the frame index of the SC:R image ({@link SpriteImage#showFrame}); its direction offset selects the drawn frame.
     *
     * @param image the animated image
     * @param frame the frame number of the animation; how it maps to a picture (direction, mirroring) is up to the owner
     * @throws IllegalArgumentException if the image is not an SC:R image
     */
    @Override
    public void showFrame(@NotNull Image image, int frame) {
        SpriteImage.of(image).showFrame(frame);
    }

    /**
     * Plays a sound at an animated image.
     * <p>
     * Plays the sound at the position of the image's sprite, as {@code playsnd} does; the sound type's index is its sfxdata entry.
     *
     * @param image the animated image, the image of the current update
     * @param sound the sound type
     */
    @Override
    public void playSound(@NotNull Image image, @NotNull SoundType sound) {
        Sprite sprite = this.getSprite();

        this.getExecutor().playSound(sound.getIndex(), sprite.getX(), sprite.getY());
    }

    /**
     * Creates an overlay image on the object of an animated image.
     * <p>
     * Creates the overlay above the image's sprite, offset from the image, as {@code imgol} does; the image type's index is its images.dat
     * entry.
     *
     * @param image     the animated image, the image of the current update
     * @param imageType the image type of the overlay
     * @param offsetX   the horizontal offset from the animated image, in pixels
     * @param offsetY   the vertical offset from the animated image, in pixels
     * @throws UnsupportedOperationException if the update is not a bounded pooled execution
     * @throws IllegalArgumentException      if the image is not an SC:R image
     */
    @Override
    public void spawnOverlay(@NotNull Image image, @NotNull ImageType imageType, int offsetX, int offsetY) {
        IscriptExecution execution = this.getExecution();

        if (execution == null) {
            throw Exceptions.newUnsupportedOperationException("Image creation requires bounded pooled execution.");
        }

        SpriteImage reference = SpriteImage.of(image);

        this.getExecutor().createScriptImage(execution,
                                             reference,
                                             this.getSprite(),
                                             imageType.getIndex(),
                                             reference.getOffsetX() + offsetX, reference.getOffsetY() + offsetY,
                                             IscriptExecutor.ORDER_ABOVE,
                                             this.getUnit());
    }

    /**
     * Fires a weapon of the unit of an animated image: the melee hit, or the launch of a projectile.
     * <p>
     * Fires the ground weapon for slot {@code 0} and the air weapon otherwise at the order target, from the weapon's own offset, as
     * {@code attackwith} does; an image without a unit fires nothing.
     *
     * @param image            the animated image, the image of the current update
     * @param weaponSlot       the weapon slot of the unit, {@code 0} for its first weapon
     * @param instructionLimit the instruction limit of each animation program run the weapon fire starts (its projectile)
     */
    @Override
    public void fireWeapon(@NotNull Image image, int weaponSlot, int instructionLimit) {
        Unit unit = this.getUnit();

        if (unit != null) {
            this.game.getUnitAttack().attackWith(unit, weaponSlot + 1, OWN_FORWARD_OFFSET, instructionLimit);
        }
    }

    /**
     * Moves the unit of an animated image forward.
     * <p>
     * Applies the distance as the unit's movement speed for the frame, after its speed modifiers, as {@code move} does; an image without a
     * unit moves nothing.
     *
     * @param image    the animated image, the image of the current update
     * @param distance the distance in pixels
     */
    @Override
    public void move(@NotNull Image image, int distance) {
        Unit unit = this.getUnit();

        if (unit != null) {
            unit.applyIscriptMovementSpeed(this.game.requireGameData().getUnitTypes(), distance << IscriptExecutor.MOVEMENT_FRACTION_BITS);
        }
    }
}
