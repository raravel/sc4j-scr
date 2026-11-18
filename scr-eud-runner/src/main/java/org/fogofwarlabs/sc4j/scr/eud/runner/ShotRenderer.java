package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.FrameTiming;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.fogofwarlabs.sc4j.engine.client.video.VideoClipDecoder;
import org.fogofwarlabs.sc4j.scr.client.TextTables;
import org.fogofwarlabs.sc4j.scr.client.cursor.GameCursor;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleLayout;
import org.fogofwarlabs.sc4j.scr.client.ui.console.ConsoleView;
import org.fogofwarlabs.sc4j.scr.client.ui.console.MessageAreaUtils;
import org.fogofwarlabs.sc4j.scr.client.ui.dialog.SheetFrameRecords;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitPlayer;
import org.fogofwarlabs.sc4j.scr.client.ui.portrait.PortraitTables;
import org.fogofwarlabs.sc4j.scr.client.world.ClientCamera;
import org.fogofwarlabs.sc4j.scr.client.world.GameViewport;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.client.world.ScrWorldPass;
import org.fogofwarlabs.sc4j.scr.client.world.WorldOverlayState;
import org.fogofwarlabs.sc4j.scr.data.format.casc.CascStorage;
import org.fogofwarlabs.sc4j.scr.data.format.text.LegacyTextUtils;
import org.fogofwarlabs.sc4j.scr.data.resource.ScrResourceProvider;
import org.fogofwarlabs.sc4j.scr.game.player.Player;
import org.fogofwarlabs.sc4j.scr.game.player.PlayerTable;
import org.fogofwarlabs.sc4j.scr.game.presentation.InterfaceTimers;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.fogofwarlabs.sc4j.scr.render.ClassicSceneRenderer;
import org.fogofwarlabs.sc4j.scr.render.OverlayPass;
import org.fogofwarlabs.sc4j.scr.render.WorldRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceAssets;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFont;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceFonts;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceRenderer;
import org.fogofwarlabs.sc4j.scr.render.ui.InterfaceText;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Draws what the local player sees as the client draws a classic (SD) frame: the world of {@link ScrWorldPass} (terrain, sprites, selection,
 * fog) with the console of the client's {@link ConsoleView} (HUD, minimap, selection panel, portrait, command card, resources, message lines,
 * countdown and leaderboard) into the 640x480 SD target, presented to a hidden 640x480 window and read back.
 * <p>
 * Everything that needs OpenGL lives on one render thread, made at the first shot: the window, its context, the renderers and the console.
 * The simulation thread waits for each draw within a bound; while it waits the game does not run, so the draw reads a still game. A failure
 * or a draw that does not end in time makes the renderer unavailable for the rest of the run.
 */
final class ShotRenderer implements AutoCloseable {
    /**
     * The picture size: the classic game screen.
     */
    static final int WIDTH = LiveMapSession.VIEWPORT_WIDTH;
    static final int HEIGHT = LiveMapSession.VIEWPORT_HEIGHT;
    /**
     * The wall time the first shot may spend creating the window, the context and the renderers.
     */
    static final long INITIALIZATION_TIMEOUT_MILLIS = 30_000;
    /**
     * The wall time one draw may take.
     */
    static final long DRAW_TIMEOUT_MILLIS = 15_000;

    private static final int OPAQUE_ARGB = 0xFF000000;
    /**
     * The message rows' LTEXT format and the status line's CTEXT format ({@code ConsoleView}).
     */
    private static final int MESSAGE_FORMAT = InterfaceText.LEFT | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;
    private static final int STATUS_FORMAT = InterfaceText.CENTER | InterfaceText.TOP | InterfaceText.NO_BASELINE_TWEAK;

    private final @NotNull CascStorage storage;
    private final @NotNull ScrResourceProvider resources;
    private final @NotNull String locale;
    private final @NotNull ExecutorService thread;

    private @Nullable String unavailable;
    private boolean initialized;
    private final @NotNull List<String> warnings;

    private @Nullable ClientHost host;
    private @Nullable HostWindow window;
    private @Nullable InterfaceRenderer interfaceRenderer;
    private @Nullable InterfaceFonts fonts;
    private @Nullable GameCursor cursor;
    private @Nullable ConsoleView console;
    private @Nullable WorldRenderer world;
    private @Nullable ClassicSceneRenderer scene;
    private @Nullable ScrWorldPass pass;
    private @Nullable ClientInterface shown;

    /**
     * Creates a renderer; nothing is opened until the first shot.
     *
     * @param storage   the installed storage
     * @param resources the installation's resources, with the game data's added graphics
     * @param locale    the client's text locale
     */
    ShotRenderer(@NotNull CascStorage storage, @NotNull ScrResourceProvider resources, @NotNull String locale) {
        this.storage   = storage;
        this.resources = resources;
        this.locale    = locale;
        this.thread    = Executors.newSingleThreadExecutor(task -> {
            Thread render = new Thread(task, "eud-runner-render");

            render.setDaemon(true);

            return render;
        });
        this.warnings  = new ArrayList<>();
    }

    /**
     * @return the asset warnings of the draws so far (a missing portrait, a console image)
     */
    @NotNull List<String> getWarnings() {
        synchronized (this.warnings) {
            return List.copyOf(this.warnings);
        }
    }

    /**
     * Draws the current frame and writes it as a PNG file. The caller's game must not run until this returns.
     *
     * @param ui   the client interface of the run
     * @param file the PNG file
     * @throws IOException if the shot is unavailable or fails; the message is the reason
     */
    void shoot(@NotNull ClientInterface ui, @NotNull Path file) throws IOException {
        if (this.unavailable != null) {
            throw new IOException(this.unavailable);
        }

        if (!this.initialized) {
            this.await(() -> {
                this.initialize(ui);

                return null;
            }, INITIALIZATION_TIMEOUT_MILLIS);
            this.initialized = true;
        }

        this.await(() -> {
            this.draw(ui, file);

            return null;
        }, DRAW_TIMEOUT_MILLIS);
    }

    private void await(@NotNull Callable<Void> task, long timeoutMillis) throws IOException {
        Future<Void> future = this.thread.submit(task);

        try {
            future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            this.unavailable = "the renderer did not answer within " + timeoutMillis + " ms";
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;

            this.unavailable = cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : "");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            this.unavailable = "interrupted";
        }

        if (this.unavailable != null) {
            throw new IOException(this.unavailable);
        }
    }

    /**
     * Opens the hidden window and its OpenGL 3.3 context, the world renderer of the map and the console of its tileset (render thread).
     */
    private void initialize(@NotNull ClientInterface ui) throws IOException {
        ClientHost clientHost = ClientHost.initialize();

        this.host = clientHost;

        HostWindow hostWindow = clientHost.createWindow(WindowSettings.builder("eud-runner shot", WIDTH, HEIGHT)
                                                                      .setScaledToMonitor(false)
                                                                      .build());

        this.window = hostWindow;
        hostWindow.makeContextCurrent();
        clientHost.createCapabilities();

        GameMap map = ui.getView().getMap();
        ScrResourceProvider borrowed = ScrResourceProvider.borrow(this.storage, ui.getView().requireGameData().getAddedGraphics());
        WorldRenderer worldRenderer = new WorldRenderer();

        this.world = worldRenderer;
        worldRenderer.loadMap(borrowed, map.getTileset(), map.getWidth(), map.getHeight(), map.copyTileIdentifiersForRendering());
        this.scene = new ClassicSceneRenderer(worldRenderer, borrowed, map.getTileset());

        InterfaceRenderer renderer = new InterfaceRenderer();

        this.interfaceRenderer = renderer;
        this.fonts             = new InterfaceFonts(this.resources, renderer, this.locale);
        this.cursor            = new GameCursor(this.storage, renderer);

        TextTables text = TextTables.load(this.storage, this.locale);
        Map<Integer, InterfaceAssets> tilesets = new HashMap<>();
        ConsoleView view = new ConsoleView(this.storage, renderer, this.fonts, this.cursor, text.getStatText(), new InterfaceTimers(),
                                           new SheetFrameRecords(this.storage), tileset -> {
            InterfaceAssets assets = tilesets.get(tileset);

            if (assets == null) {
                assets = InterfaceAssets.load(this.resources, tileset);
                tilesets.put(tileset, assets);
            }

            return assets;
        });

        this.console = view;
        view.setGluText(text.getGluText());
        view.setTextLocale(this.locale);
        view.setControlText(text.getControl());
        view.setNetworkText(text.getNetwork());

        try {
            view.setPortraitPlayer(new PortraitPlayer(this.storage, PortraitTables.load(this.storage), VideoClipDecoder.getDefault()));
        } catch (IOException | RuntimeException | LinkageError exception) {
            this.warn("Portraits unavailable: " + exception.getMessage());
        }

        view.setHdScale(0);
        view.setOriginalAspectRatio(false);
        view.loadTileset(map.getTileset());

        this.pass = new ScrWorldPass(new ScrWorldPass.Context() {
            @Override
            public @NotNull ScrGameView getView() {
                return ShotRenderer.this.requireShown().getView();
            }

            @Override
            public boolean isPlaybackRequested() {
                return true;
            }

            @Override
            public @Nullable WorldOverlayState prepareOverlay() {
                return ShotRenderer.this.requireShown().getWorld();
            }

            @Override
            public int getPlayerColor(int owner) {
                PlayerTable players = ShotRenderer.this.requireShown().getView().getPlayerTable();
                Player player = owner >= 0 && owner < PlayerTable.PLAYER_SLOTS ? players.getPlayer(owner) : null;

                return player != null ? player.getColor() : owner;
            }

            @Override
            public void setAssetWarning(@NotNull String warning) {
                ShotRenderer.this.warn(warning);
            }

            @Override
            public @Nullable FrameTiming getFrameTiming() {
                return null;
            }
        }, List.of());
    }

    private @NotNull ClientInterface requireShown() {
        ClientInterface current = this.shown;

        if (current == null) {
            throw new IllegalStateException("No shot is being drawn.");
        }

        return current;
    }

    /**
     * Draws one SD frame as the client's {@code drawFrame} does and writes the window's pixels (render thread).
     */
    private void draw(@NotNull ClientInterface ui, @NotNull Path file) throws IOException {
        WorldRenderer worldRenderer = requireNonNull(this.world);
        ConsoleView view = requireNonNull(this.console);
        InterfaceRenderer renderer = requireNonNull(this.interfaceRenderer);
        GameViewport viewport = ui.getViewport();
        ClientCamera camera = ui.getCamera();

        ui.prepareShot();
        this.shown = ui;

        try {
            worldRenderer.setRenderScaleLimit(1);
            // SD graphics draw the interface into their target before the present.
            worldRenderer.setSdInterfaceDeferred(true);
            camera.applyViewport();
            worldRenderer.beginFrame(WIDTH, HEIGHT, viewport.getWindowWidth(), viewport.getWindowHeight(), camera.getX(), camera.getY(),
                                     camera.getZoom());
            requireNonNull(this.pass).draw(requireNonNull(this.scene), worldRenderer,
                                           Math.max(1, viewport.getWindowWidth()), Math.max(1, viewport.getWindowHeight()),
                                           camera.getX(), camera.getY(), camera.getZoom());
            worldRenderer.finishWorld();

            if (worldRenderer.isSdPresentPending()) {
                int framebuffer = worldRenderer.beginSdInterface();

                renderer.begin(framebuffer, worldRenderer.getSdInterfaceWidth(), worldRenderer.getSdInterfaceHeight(), WIDTH, HEIGHT);
                renderer.setClip(Math.round(viewport.getGameLeft()), 0, Math.round(viewport.getGameWidth()), HEIGHT);
                view.setWindowed(true);
                view.setMultiplayerButtons(false);
                view.setMultiplayerGame(false);
                view.draw(ui.getModel(), viewport, camera);
                this.drawMessages(view, ui.getModel().getPresentation().getInterfaceLink().getMessageLines());
                renderer.end();
                worldRenderer.presentSd();
            }

            worldRenderer.endFrame();
        } finally {
            this.shown = null;
        }

        this.write(file);
    }

    /**
     * Draws the message area as {@code ConsoleView.drawMessages} does (font, rows, margins, colors, the status line), without its expiry: the
     * console expires lines against the wall clock, the run's lines expire against the game clock as the frames pass, and a shot must leave the
     * game unchanged.
     */
    private void drawMessages(@NotNull ConsoleView view, @NotNull MessageLines lines) {
        ConsoleLayout console = view.getLayout();
        InterfaceRenderer renderer = requireNonNull(this.interfaceRenderer);
        InterfaceFont font = requireNonNull(this.fonts).getFont(InterfaceFonts.NORMAL);

        if (console == null) {
            return;
        }

        float width = console.getInterfaceWidth();
        int next = lines.getNextLine();
        int oldest = next < MessageLines.ROTATING_LINES ? next : 0;

        for (int row = 0; row < MessageLines.ROTATING_LINES; row++) {
            int line = (oldest + row) % MessageLines.ROTATING_LINES;
            byte[] bytes = lines.getLine(line);

            if (bytes.length == 0) {
                continue;
            }

            float y = MessageAreaUtils.FIRST_ROW + MessageAreaUtils.ROW_STEP * row;
            int color = lines.getColor(line) != 0 ? lines.getColor(line) : InterfaceFont.getCodeColor(lines.getStyle(line));

            InterfaceText.drawColored(renderer, console, font, LegacyTextUtils.decode(bytes, 0, bytes.length),
                                      MessageAreaUtils.MARGIN, y,
                                      width - 2 * MessageAreaUtils.MARGIN + 1, MessageAreaUtils.ROW_STEP,
                                      MESSAGE_FORMAT, color, InterfaceText.GAME_CODE_COLORS, true);
        }

        byte[] status = lines.getLine(MessageLines.SYSTEM_LINE);

        if (status.length > 0) {
            InterfaceText.draw(renderer, console, font, LegacyTextUtils.decode(status, 0, status.length),
                               0, MessageAreaUtils.STATUS_ROW, console.getInterfaceWidth(), MessageAreaUtils.ROW_STEP,
                               STATUS_FORMAT, MessageLines.STYLE_SYSTEM_LINE, false);
        }
    }

    private void write(@NotNull Path file) throws IOException {
        ByteBuffer pixels = MemoryUtil.memAlloc(WIDTH * HEIGHT * OverlayPass.RGBA_BYTES);

        try {
            GL11C.glReadBuffer(GL11C.GL_BACK);
            GL11C.glReadPixels(0, 0, WIDTH, HEIGHT, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, pixels);

            BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);

            for (int vertical = 0; vertical < HEIGHT; vertical++) {
                for (int horizontal = 0; horizontal < WIDTH; horizontal++) {
                    int offset = (vertical * WIDTH + horizontal) * OverlayPass.RGBA_BYTES;
                    int color = OPAQUE_ARGB | Byte.toUnsignedInt(pixels.get(offset)) << Short.SIZE |
                                Byte.toUnsignedInt(pixels.get(offset + OverlayPass.RGBA_GREEN)) << Byte.SIZE |
                                Byte.toUnsignedInt(pixels.get(offset + OverlayPass.RGBA_BLUE));

                    image.setRGB(horizontal, HEIGHT - vertical - 1, color);
                }
            }

            Path parent = file.toAbsolutePath().getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");

            if (!ImageIO.write(image, "png", temporary.toFile())) {
                throw new IOException("No PNG writer.");
            }

            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private void warn(@NotNull String warning) {
        synchronized (this.warnings) {
            if (!this.warnings.contains(warning) && this.warnings.size() < 20) {
                this.warnings.add(warning);
            }
        }
    }

    private static <T> @NotNull T requireNonNull(@Nullable T value) {
        if (value == null) {
            throw new IllegalStateException("The renderer is not initialized.");
        }

        return value;
    }

    /**
     * Releases the renderers and the window on the render thread when it still answers; the process ends soon after in any case.
     */
    @Override
    public void close() {
        if (this.host != null && this.unavailable == null) {
            Future<?> closing = this.thread.submit(() -> {
                for (AutoCloseable resource : new AutoCloseable[]{this.console, this.scene, this.world, this.cursor, this.fonts,
                                                                  this.interfaceRenderer, this.window, this.host}) {
                    if (resource != null) {
                        try {
                            resource.close();
                        } catch (Exception ignored) {
                            // Closing at the end of the run; a failed release changes nothing the report holds.
                        }
                    }
                }
            });

            try {
                closing.get(5, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException exception) {
                // The process ends right after the report.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        this.thread.shutdownNow();
    }
}
