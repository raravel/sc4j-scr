package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameSetup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Extends running games of the SC:R mod with simulation plugins, per-game state that takes part in the simulation: it hears the frames, the
 * events and the mod commands of the game and decides at the decision points of its routines ({@link ScrSimulationPlugin}). A mod built from
 * the SC:R mod's sources adds an implementation with a public no-argument constructor and names it in the resource
 * {@code META-INF/services/org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationExtension} of its jar; {@link ScrSimulationExtensionUtils}
 * calls the named implementations in the order of that file. The SC:R mod itself names none.
 * <p>
 * A mod with a simulation extension runs games SC:R does not run, so it needs a game identity of its own, as a mod whose game extension provides
 * memory ({@link ScrGameExtension#providesMemory()}): its own LAN join version (the SC:R mod's LAN lobby refuses to start without one) besides
 * its own mod identity for rooms and replays.
 */
public interface ScrSimulationExtension {
    /**
     * Creates the extension's simulation plugin of a game, once, when the game's SC:R system attaches; the plugin lives as long as the game, so
     * every new game, live, replay or loaded save, starts with a new plugin.
     * <p>
     * At this point the game offers its creation inputs ({@link Game#getSetup()}): the game data, the map, the players, the game options and the
     * scenario the map was built from ({@link GameSetup#getScenario()}; for a loaded save, the scenario built from the save; {@code null} for a
     * game without one), so an extension can return {@code null} for maps it does not handle. A live game started from a map file also offers the
     * map's archive ({@link GameSetup#getMapArchive()}), and a replay played back offers the mod sections a game of the mod stored in it
     * ({@link GameSetup#readReplaySection}, {@link Game#setReplaySection}). The map objects and units are not created, the trigger locations are
     * not loaded and the map model is not set yet.
     *
     * @param game the game, attached and not yet initialized
     * @return the created plugin, or {@code null} if the game needs none
     */
    @Nullable ScrSimulationPlugin createPlugin(@NotNull Game game);
}
