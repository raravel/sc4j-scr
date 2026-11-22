package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemorySource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extends running games of the SC:R mod with plugins, per-game state that observes the game and changes how it is shown, and with memory sources,
 * EUD memory that is part of the simulation. A mod built from the SC:R mod's sources adds an implementation with a public no-argument constructor
 * and names it in the resource {@code META-INF/services/org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension} of its jar;
 * {@link ScrGameExtensionUtils} calls the named implementations in the order of that file. The SC:R mod itself names none.
 * <p>
 * A mod whose extensions only create plugins plays with SC:R players and SC:R replays. A mod with an extension that provides memory
 * ({@link #providesMemory()}) runs games SC:R does not run, so it needs a game identity of its own: its own LAN join version (the SC:R mod's
 * LAN lobby refuses to start without one) besides its own mod identity for rooms and replays.
 */
public interface ScrGameExtension {
    /**
     * Creates the extension's plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so every new
     * game, live, replay or loaded save, starts with a new plugin.
     *
     * @param game the game, attached and not yet initialized
     * @return the created plugin, or {@code null} if the game needs none
     */
    @Nullable ScrGamePlugin createPlugin(@NotNull Game game);

    /**
     * Tells whether the extension provides EUD memory to the simulation ({@link #createMemorySource}); it answers the same for every game, so
     * the mod's game identity is known before any game starts.
     *
     * @return {@code true} if the extension creates memory sources, {@code false} for an extension with plugins only
     */
    default boolean providesMemory() {
        return false;
    }

    /**
     * Creates the extension's memory source of a game, once, when the game's SC:R system attaches; it is called only if
     * {@link #providesMemory()} answers {@code true}. The source provides its regions when the game's EUD address space is created.
     *
     * @param game the game, attached and not yet initialized
     * @return the created memory source, or {@code null} if the game needs none
     */
    default @Nullable ClassicMemorySource createMemorySource(@NotNull Game game) {
        return null;
    }
}
