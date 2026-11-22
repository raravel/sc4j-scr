package org.fogofwarlabs.sc4j.screnhanced.script.core;

import org.fogofwarlabs.sc4j.scr.content.ScrUnitIds;
import org.fogofwarlabs.sc4j.scr.data.format.dat.SfxdataDat;
import org.fogofwarlabs.sc4j.scr.game.presentation.SoundEvents;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

import java.util.ArrayList;
import java.util.List;

/**
 * Defines {@code core.sound}: the game sounds a map script plays. The functions run in shared and local code; shared code plays a sound on every
 * peer, local code only on the local machine. They change no game state.
 * <p>
 * {@code playAt(soundId, x, y)} plays a sound of {@code sfxdata.dat}, from {@code 0} to {@value SfxdataDat#SOUND_COUNT} minus one, at a map
 * position as an animation script plays its sounds ({@link SoundEvents#add}, without a source unit): the interface plays it by its distance from
 * the screen, louder the nearer it is, whether the players see the position or not. The game plays the sounds of a game loop iteration after it,
 * so a sound of local code, which runs between the iterations, waits for the next frame start ({@link #playLocalSounds()}).
 */
final class ScriptSoundCore {
    /**
     * The values of a sound that waits: its id and its map position.
     */
    private static final int SOUND_ID = 0;
    private static final int SOUND_X = 1;
    private static final int SOUND_Y = 2;
    private static final int SOUND_VALUES = 3;

    private final @NotNull ScriptGame scriptGame;
    private final @NotNull List<int[]> localSounds;

    /**
     * Creates a {@link ScriptSoundCore} instance without waiting sounds.
     *
     * @param scriptGame the game binding
     */
    ScriptSoundCore(@NotNull ScriptGame scriptGame) {
        this.scriptGame  = scriptGame;
        this.localSounds = new ArrayList<>();
    }

    /**
     * Defines the functions of {@code core.sound}.
     *
     * @param namespace the namespace object
     * @param global    the global scope
     */
    void define(@NotNull Scriptable namespace, @NotNull Scriptable global) {
        ScriptCoreUtils.defineFunction(global, namespace, "playAt", 3, (context, scope, thisObject, arguments) -> {
            int soundId = ScriptCoreUtils.requireId("sound", ScriptCoreUtils.readInteger(arguments, 0), SfxdataDat.SOUND_COUNT);
            int x = ScriptCoreUtils.readInteger(arguments, 1);
            int y = ScriptCoreUtils.readInteger(arguments, 2);

            if (this.scriptGame.isLocal()) {
                int[] sound = new int[SOUND_VALUES];

                sound[SOUND_ID] = soundId;
                sound[SOUND_X]  = x;
                sound[SOUND_Y]  = y;

                this.localSounds.add(sound);
            } else {
                this.getSoundEvents().add(soundId, x, y, ScrUnitIds.NONE);
            }

            return Context.getUndefinedValue();
        });
    }

    /**
     * Plays the sounds local code played since the previous call, in order, with the sounds of the current game loop iteration.
     */
    void playLocalSounds() {
        SoundEvents events = this.getSoundEvents();

        for (int[] sound : this.localSounds) {
            events.add(sound[SOUND_ID], sound[SOUND_X], sound[SOUND_Y], ScrUnitIds.NONE);
        }

        this.localSounds.clear();
    }

    private @NotNull SoundEvents getSoundEvents() {
        return this.scriptGame.getGame().getInterfaceLink().getSoundEvents();
    }
}
