package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;

/**
 * Tests the release status of the main menu: a fixed status keeps its state, and a checked status whose read fails ends up unknown.
 */
final class ReleaseStatusTest {
    private static final @NotNull ReleaseVersion RUNNING_VERSION = parseVersion("0.1.3");
    private static final @NotNull ReleaseVersion LATEST_VERSION = parseVersion("0.1.4");
    private static final long CHECK_TIMEOUT_MILLISECONDS = 10_000;
    private static final long POLL_MILLISECONDS = 10;

    private static @NotNull ReleaseVersion parseVersion(@NotNull String text) {
        ReleaseVersion version = ReleaseVersion.parse(text);

        Assertions.assertNotNull(version, text);

        return version;
    }

    @Test
    void of_should_keep_the_state_and_refuse_a_latest_version_that_does_not_go_with_it() {
        ReleaseStatus status = ReleaseStatus.of(RUNNING_VERSION, ReleaseState.UPDATE_AVAILABLE, LATEST_VERSION);

        Assertions.assertSame(RUNNING_VERSION, status.getRunningVersion());
        Assertions.assertEquals(ReleaseState.UPDATE_AVAILABLE, status.getState());
        Assertions.assertSame(LATEST_VERSION, status.getLatestVersion());
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReleaseStatus.of(RUNNING_VERSION, ReleaseState.CHECKING, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReleaseStatus.of(RUNNING_VERSION, ReleaseState.UP_TO_DATE, null));
        Assertions.assertThrows(IllegalArgumentException.class, () -> ReleaseStatus.of(RUNNING_VERSION, ReleaseState.UNKNOWN, LATEST_VERSION));
    }

    @Test
    void check_should_end_unknown_when_the_release_cannot_be_read() throws IOException, InterruptedException {
        int closedPort;

        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        ReleaseStatus status = ReleaseStatus.check(RUNNING_VERSION, URI.create("http://127.0.0.1:" + closedPort + "/"),
                                                   ReleaseRepository.of("owner", "name"));
        long deadline = System.currentTimeMillis() + CHECK_TIMEOUT_MILLISECONDS;

        while (status.getState() == ReleaseState.CHECKING && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLISECONDS);
        }

        Assertions.assertEquals(ReleaseState.UNKNOWN, status.getState());
        Assertions.assertNull(status.getLatestVersion());
    }
}
