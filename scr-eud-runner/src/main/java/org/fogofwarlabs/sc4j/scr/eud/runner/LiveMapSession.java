package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.base.json.JsonReader;
import org.fogofwarlabs.sc4j.scr.data.format.chk.ChkFile;
import org.fogofwarlabs.sc4j.scr.data.type.constant.ButtonSetIds;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.replay.GameSourceFile;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplayRuntime;
import org.fogofwarlabs.sc4j.scr.game.replay.ReplaySession;
import org.fogofwarlabs.sc4j.scr.game.session.LiveGame;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.region.LocalInterfaceState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A live single-player game on a map file without a client: the interface state a client supplies is filled in before the session starts,
 * and frames advance in the client's turn-exchange, simulation and outcome order. This follows the scr-game test fixture
 * {@code LiveMapGameScenario}, outside the test sources.
 */
final class LiveMapSession {
    /**
     * The classic game screen size the EUD addresses describe.
     */
    static final int VIEWPORT_WIDTH = 640;
    static final int VIEWPORT_HEIGHT = 480;
    /**
     * The order and animation script budget of a frame.
     */
    static final int INSTRUCTION_LIMIT = 100_000;

    private static final @NotNull String NETWORK_TEXT_PATH = "locales/enUS/Assets/rez/network.json";
    private static final @NotNull String STATUS_TEXT_PATH = "locales/enUS/Assets/rez/stat_txt.json";

    private final @NotNull ReplayRuntime runtime;
    private final @NotNull LiveGame live;

    private LiveMapSession(@NotNull ReplayRuntime runtime, @NotNull LiveGame live) {
        this.runtime = runtime;
        this.live    = live;
    }

    /**
     * Starts the live game of a session.
     *
     * @param gameData the installed game data
     * @param live     the live session
     * @param mapPath  the map file
     * @param layout   the client's message line layout
     * @return the started session
     * @throws IOException           if the map or the installed texts cannot be read
     * @throws IllegalStateException if the game fails at startup
     */
    static @NotNull LiveMapSession start(@NotNull GameData gameData, @NotNull LiveGame live, @NotNull Path mapPath,
                                         MessageLines.@NotNull TextLayout layout) throws IOException {
        ReplayRuntime runtime = ReplayRuntime.createLiveGame(live.getReplay(),
                                                             ChkFile.parseTolerant(live.getSetup().getMapData()),
                                                             gameData,
                                                             live.getLocalPlayer(),
                                                             false,
                                                             live.getTeamSlotRaces(),
                                                             VIEWPORT_WIDTH, VIEWPORT_HEIGHT,
                                                             INSTRUCTION_LIMIT);

        prepareInterface(runtime.getGame(), gameData, layout);

        runtime.setSourceFile(GameSourceFile.read(mapPath));
        live.startSession(runtime.getGame(), runtime.getFrameTiming());
        runtime.start(0, 0);

        if (runtime.getFailure() != null) {
            throw new IllegalStateException("The game failed at startup.", runtime.getFailure());
        }

        ReplaySession session = runtime.getSession();

        if (session == null) {
            throw new IllegalStateException("The game has no session after startup.");
        }

        live.attachReplaySession(session);

        return new LiveMapSession(runtime, live);
    }

    /**
     * @return the game
     */
    @NotNull Game getGame() {
        return this.runtime.getGame();
    }

    /**
     * @return the live session, which takes the local player's commands
     */
    @NotNull LiveGame getLive() {
        return this.live;
    }

    /**
     * @return the game speed configuration
     */
    @NotNull GameSpeed getFrameTiming() {
        return this.runtime.getFrameTiming();
    }

    /**
     * @return the failure that stopped the game, or {@code null}
     */
    @Nullable Exception getFailure() {
        return this.runtime.getFailure();
    }

    /**
     * @return the outcome screen the local player reached: {@code null} while the game runs, else {@code defeat} or {@code victory}
     */
    @Nullable String getOutcome() {
        return switch (this.live.getLocalOutcome()) {
            case 0 -> null;
            case 2 -> "defeat";
            default -> "victory";
        };
    }

    /**
     * Advances one frame.
     */
    void stepFrame() {
        int frame = this.runtime.getGame().getCurrentFrame();

        this.live.exchangeTurn(frame);
        this.runtime.getGame().getInterfaceLink().getMessageLines().expire(frame * GameSpeed.FASTEST_DURATION);
        this.runtime.step(GameSpeed.FASTEST_DURATION, frame * GameSpeed.FASTEST_DURATION);
        this.live.updateOutcome(this.runtime.getGame());
    }

    private static void prepareInterface(@NotNull Game game, @NotNull GameData gameData, MessageLines.@NotNull TextLayout layout)
            throws IOException {
        game.getInterfaceLink().getLocalKeyStates().setAllReleased();
        game.getInterfaceLink().getLocalView().setView(0, 0, VIEWPORT_WIDTH, VIEWPORT_HEIGHT);

        LocalInterfaceState local = game.getInterfaceLink().getLocalInterface();

        local.startInterfaceTick(0);
        local.setInterfaceSelection(new int[UnitSelection.MAXIMUM_SELECTED]);
        local.setPortraitRefresh(1);
        local.setStallStamp(0);
        local.setChatLabel(new byte[LocalInterfaceState.CHAT_LABEL_SIZE]);
        local.setStatusPanelCache(new int[LocalInterfaceState.STATUS_PANEL_CACHE_DWORDS]);
        local.setMouseButtons(0);
        local.setChatMode(0);
        local.setGluePalette(0);
        local.setDisplayedButtonSet(ButtonSetIds.EMPTY);

        MessageLines lines = game.getInterfaceLink().getMessageLines();

        // The client's own wrapping, so a long line takes the rows it takes on the client's screen (MessageLayout).
        lines.setLayout(layout);
        lines.setHostClock(() -> game.getCurrentFrame() * GameSpeed.FASTEST_DURATION);
        lines.setSubtitles(Boolean.TRUE);
        lines.setDepartureFormats("%s has left the game.", "%s was dropped from the game.");

        Map<String, byte[]> keyed = new HashMap<>();
        List<byte[]> numbered = new ArrayList<>();

        if (JsonReader.parse(gameData.readInstalledFile(NETWORK_TEXT_PATH)) instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> map && map.get("Key") instanceof String key && map.get("Value") instanceof String value) {
                    keyed.put(key, value.getBytes(StandardCharsets.UTF_8));
                }
            }
        }

        if (JsonReader.parse(gameData.readInstalledFile(STATUS_TEXT_PATH)) instanceof List<?> entries) {
            for (Object entry : entries) {
                numbered.add(entry instanceof Map<?, ?> map && map.get("Value") instanceof String value
                             ? value.getBytes(StandardCharsets.UTF_8)
                             : null);
            }
        }

        lines.setStatusTexts(new MessageLines.StatusTexts() {
            @Override
            public byte @Nullable [] resolveKeyedText(@NotNull String key) {
                return keyed.get(key);
            }

            @Override
            public byte @Nullable [] resolveNumberedText(int textIdentifier) {
                return textIdentifier >= 1 && textIdentifier <= numbered.size() ? numbered.get(textIdentifier - 1) : null;
            }
        });
    }
}
