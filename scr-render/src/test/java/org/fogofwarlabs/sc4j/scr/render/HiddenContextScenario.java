package org.fogofwarlabs.sc4j.scr.render;

import org.fogofwarlabs.sc4j.engine.client.host.ClientHost;
import org.fogofwarlabs.sc4j.engine.client.host.HostWindow;
import org.fogofwarlabs.sc4j.engine.client.host.WindowSettings;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * Runs the GPU checks of the render tests on a hidden OpenGL 3.3 core context of the engine host.
 */
public final class HiddenContextScenario {
    /**
     * A check that runs on the hidden context.
     */
    public interface Check {
        /**
         * Runs the check while the hidden context is current.
         *
         * @throws IOException if an installed asset cannot be read
         */
        void run() throws IOException;
    }

    /**
     * Runs a check on a hidden window whose OpenGL 3.3 core context is current, then closes the host: the window, the context capabilities and
     * the windowing library.
     *
     * @param width  the window width in pixels
     * @param height the window height in pixels
     * @param title  the window title
     * @param check  the check
     * @throws IOException if the check cannot read an installed asset
     */
    public static void run(int width, int height, @NotNull String title, @NotNull Check check) throws IOException {
        try (ClientHost host = ClientHost.initialize()) {
            HostWindow window = host.createWindow(WindowSettings.builder(title, width, height).build());

            window.makeContextCurrent();
            host.createCapabilities();

            check.run();
        }
    }

    private HiddenContextScenario() {
    }
}
