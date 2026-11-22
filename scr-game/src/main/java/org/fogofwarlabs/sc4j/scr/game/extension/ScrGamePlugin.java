package org.fogofwarlabs.sc4j.scr.game.extension;

import org.fogofwarlabs.sc4j.engine.resource.graphics.Picture;
import org.fogofwarlabs.sc4j.engine.resource.graphics.SpriteSheet;
import org.fogofwarlabs.sc4j.scr.data.format.terrain.TerrainTileset;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageAnimation;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrImageSheet;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceLink;
import org.fogofwarlabs.sc4j.scr.game.sprite.SpriteImage;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The per-game state of a {@link ScrGameExtension}. A plugin reads the game and keeps presentation state of its own; it changes no simulation
 * state, so the state hash, saves, replays and network games are the same with or without it. Its methods run on the thread that runs and draws
 * the game.
 * <p>
 * A plugin turns local input into simulation input only through mod commands: it sees the local keyboard, mouse, chat lines and command card
 * buttons ({@link #onKeyInput}, {@link #onMouseInput}, {@link #onChatInput}, {@link #onButtonInput}) and queues mod commands of the local player
 * through the game's submitter ({@link InterfaceLink#getModCommandSubmitter()}), which a live session supplies after the plugin is created; the
 * game's simulation plugins receive them at the same frame on every peer. It reads the local camera and centers the local view through the game's local view
 * ({@link InterfaceLink#getLocalView()}): the screen position of the last frame ({@link LocalView#getScreenX()}, {@link LocalView#getScreenY()})
 * and the centering of the network game's Center View trigger action ({@link LocalView#centerScreen}), which the client applies after the input
 * method or the frame that called it. It reads the game screen the client last drew, its size, scale, graphics and font measure, through the
 * game's local display ({@link InterfaceLink#getLocalDisplay()}).
 */
public interface ScrGamePlugin {
    /**
     * Called when the game's EUD address space is created, on the first EUD access of the game's triggers; the plugin may observe writes and
     * read memory through the view from then on.
     *
     * @param memory the read-only view of the address space
     */
    default void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
    }

    /**
     * Called when the local player presses or releases a key in a running game, live or replayed, before the game's own keys see the press: the
     * command card hotkeys, the control group and screen position keys, the speed keys and Escape. The plugins see an input in extension order
     * until one consumes it. The client delivers a press only while the game takes play keys: not while the chat box is open, a game menu or
     * dialog is open, or the triggers pause the game. It does not deliver the keys that open the chat box (Enter) and the game menu (F10), the
     * presses that run an interface accelerator (the menus and their dialogs, the sound, music and graphics toggles, the chat history), nor the
     * repeats of a held key.
     * <p>
     * A release is delivered when its press was, also if the chat box opened in between; a release reaches no game key, so its consumption has no
     * effect.
     *
     * @param input the key input
     * @return {@code true} if the plugin consumed the press, which then reaches neither the later plugins nor the game, {@code false} otherwise
     */
    default boolean onKeyInput(@NotNull ScrKeyInput input) {
        return false;
    }

    /**
     * Called when the local player presses or releases a mouse button over the map of a running game, live or replayed, before the game's own
     * handling: selection, orders, targeting and placement. The plugins see an input in extension order until one consumes it. The client delivers
     * a press over the map view only: not over the console, its minimap and dialogs, the replay controls or a game menu. A release is delivered when
     * its press was, wherever the pointer is; it reaches the game when its press did, whatever the plugin returns for it.
     *
     * @param input the mouse input
     * @return {@code true} if the plugin consumed the press, which then reaches neither the later plugins nor the game, {@code false} otherwise
     */
    default boolean onMouseInput(@NotNull ScrMouseInput input) {
        return false;
    }

    /**
     * Called when the local player submits a chat line in a running live game, before the game's own handling of the line: the cheat codes, then
     * the chat to the chosen recipients. The plugins see a line in extension order until one consumes it. The client does not deliver the empty
     * lines nor the lines of its own commands ({@code /} lines).
     *
     * @param text the typed line
     * @return {@code true} if the plugin consumed the line, which then reaches neither the later plugins nor the game, {@code false} otherwise
     */
    default boolean onChatInput(@NotNull String text) {
        return false;
    }

    /**
     * Called when the local player presses an enabled command card button in a running live game, by a mouse click or a hotkey, before the
     * button's action runs; and when the local player targets a map point or unit with a script target button, instead of an order. The plugins
     * see an input in extension order until one consumes it.
     * <p>
     * A consumed press runs no action: no command, no targeting or placement mode, no card change. A consumed target leaves the targeting mode
     * waiting for another target, as a refused target of an order does. A press of a script button runs nothing itself, and the targeting mode
     * of a script target button ends at a target that no plugin consumed.
     *
     * @param input the button input
     * @return {@code true} if the plugin consumed the input, which then reaches neither the later plugins nor the game, {@code false} otherwise
     */
    default boolean onButtonInput(@NotNull ScrButtonInput input) {
        return false;
    }

    /**
     * Called on the local machine after each game frame the client plays, live or replayed, at every playback speed and for a single step of a
     * paused replay; not for the frames a replay seek runs. The local view and interface state are those the client supplied for the frame, and
     * the client applies a camera move of the plugin ({@link LocalView#moveScreen}) before it draws the frame.
     *
     * @param frame the number of the frame that ran, the game's current frame
     */
    default void onFramePlayed(int frame) {
    }

    /**
     * Finds the graphics a sprite image draws instead of its installed GRP: indexed frames that the image's frame index selects, decoded when drawn.
     * Selection circles, health bars and the selection tests keep the installed graphics.
     *
     * @param image an image of a drawn sprite
     * @return the frames the image draws, or {@code null} for its installed graphics
     */
    default @Nullable ScrImageSheet findImageSheet(@NotNull SpriteImage image) {
        return null;
    }

    /**
     * Finds the HD graphics a sprite image draws instead of its installed anim, in the HD and HD2 graphics; the classic graphics keep
     * {@link #findImageSheet}. Selection circles, health bars and the selection tests keep the installed graphics.
     *
     * @param image an image of a drawn sprite
     * @return the anims the image draws, or {@code null} for its installed anim
     */
    default @Nullable ScrImageAnimation findImageAnimation(@NotNull SpriteImage image) {
        return null;
    }

    /**
     * Finds the image type whose installed graphics a sprite image draws instead of its own, in every graphics: its GRP, its anims, their frame
     * placement and lights. A sheet or anim of the image itself ({@link #findImageSheet}, {@link #findImageAnimation}) is drawn instead.
     *
     * @param image an image of a drawn sprite
     * @return the image type, or {@code -1} for the image's own
     */
    default int findImageTypeId(@NotNull SpriteImage image) {
        return -1;
    }

    /**
     * Finds the frame a sprite image draws instead of its own drawn frame, in its installed graphics or in its sheet ({@link #findImageSheet}).
     *
     * @param image an image of a drawn sprite
     * @return the frame index, or {@code -1} for the image's own frame
     */
    default int findImageFrame(@NotNull SpriteImage image) {
        return -1;
    }

    /**
     * Finds the draw function a sprite image draws with instead of its own ({@link SpriteImage#getModifier()}).
     *
     * @param image an image of a drawn sprite
     * @return the draw function, or {@code -1} for the image's own
     */
    default int findImageDrawFunction(@NotNull SpriteImage image) {
        return -1;
    }

    /**
     * Finds the picture a terrain megatile draws instead of its installed one: {@value TerrainTileset#TILE_SIZE} by
     * {@value TerrainTileset#TILE_SIZE} palette indices, rows from the top. The same array is returned while the picture is unchanged.
     *
     * @param megatile the megatile index of a drawn tile
     * @return the palette indices, or {@code null} for the installed picture
     */
    default byte @Nullable [] findTerrainTile(int megatile) {
        return null;
    }

    /**
     * Finds the picture the plugin draws over the game screen and its console.
     *
     * @return the overlay, or {@code null} for none
     */
    default @Nullable ScrScreenOverlay findScreenOverlay() {
        return null;
    }

    /**
     * @return {@code true} if the console is not drawn, {@code false} if it is
     */
    default boolean isConsoleHidden() {
        return false;
    }

    /**
     * Finds the resource icons the console draws instead of its installed ones: an indexed sheet whose frames the resource icon frames select,
     * {@code 0} minerals, {@code 1} to {@code 3} gas and {@code 4} to {@code 6} supply of the Zerg, Terran and Protoss. Each frame is drawn at
     * its offset in the frame box from the icon's place. The same sheet is returned while the icons are unchanged.
     *
     * @return the indexed sheet, or {@code null} for the installed icons
     */
    default @Nullable SpriteSheet findResourceIcons() {
        return null;
    }

    /**
     * Finds how the plugin places the console: moved dialogs, the shown console pixels and the dialog pictures.
     *
     * @return the placement, or {@code null} for the installed console
     */
    default @Nullable ScrConsolePlacement findConsolePlacement() {
        return null;
    }

    /**
     * Finds the picture the console draws instead of an installed art file of the shown race console: stretched over the installed file's place
     * and size. The console is hit where a drawn frame picture ({@link ScrConsoleArt#CONSOLE} with the SD graphics, the three pieces with the HD
     * and HD2 graphics) is not transparent. A replaced frame picture of the shown graphics is drawn whole even when a placement shows only some
     * console pixels ({@link ScrConsolePlacement#isMasked}); the placement still limits the dialogs. The same picture is returned while the art
     * is unchanged.
     *
     * @param art the art file
     * @return the picture, or {@code null} for the installed file
     */
    default @Nullable Picture findConsoleArt(@NotNull ScrConsoleArt art) {
        return null;
    }

    /**
     * Finds the picture the console draws for a resource icon instead of its installed icon and of the frame of {@link #findResourceIcons}: fitted
     * into the installed icon's box. The same picture is returned while the icon is unchanged. The icon frames are {@code 0} minerals,
     * {@code 1} to {@code 3} gas and {@code 4} to {@code 6} supply of the Zerg, Terran and Protoss.
     *
     * @param icon the resource icon frame
     * @return the picture, or {@code null} for the other icons
     */
    default @Nullable Picture findResourceIconPicture(int icon) {
        return null;
    }

    /**
     * Finds the shapes, texts and pictures the plugin draws over the game in the frame being drawn. The client draws the list of every plugin, in
     * extension order, at the real resolution: the {@link ScrDrawList.Space#WORLD} commands above the units and below the console, the
     * {@link ScrDrawList.Space#MINIMAP} commands above the minimap, and the {@link ScrDrawList.Space#SCREEN} commands above the console, below
     * the game menus. The client reads the list while it draws and keeps nothing of it but the uploaded pictures, so the plugin may refill the
     * same list for the next frame.
     *
     * @return the list, or {@code null} for none
     */
    default @Nullable ScrDrawList findDrawList() {
        return null;
    }
}
