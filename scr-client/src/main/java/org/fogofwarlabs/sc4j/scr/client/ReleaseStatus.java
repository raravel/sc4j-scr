package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.base.log.Logger;
import org.fogofwarlabs.sc4j.catalog.ReleaseCheck;
import org.fogofwarlabs.sc4j.catalog.ReleaseVersion;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseClient;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseException;
import org.fogofwarlabs.sc4j.catalog.github.ReleaseRepository;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tells the running SC4J version and how it stands against the latest SC4J release. A checked status reads the latest release once, on a
 * daemon thread of its own that starts when its state or latest version is first asked for; a failed read leaves the state
 * {@link ReleaseState#UNKNOWN}. The getters may be called from any thread.
 */
public final class ReleaseStatus {
    private static final @NotNull Logger LOGGER = Logger.of(ReleaseStatus.class);
    private static final @NotNull String CHECK_THREAD_NAME = "scr-release-check";

    /**
     * Creates a {@link ReleaseStatus} instance that checks the running SC4J version against the latest release of SC4J's repository on
     * GitHub when it is first asked for.
     *
     * @return the created {@link ReleaseStatus} instance
     */
    public static @NotNull ReleaseStatus checkRunningVersion() {
        return check(ReleaseVersion.CURRENT, ReleaseClient.GITHUB_API, ReleaseCheck.DEFAULT_REPOSITORY);
    }

    /**
     * Creates a {@link ReleaseStatus} instance that checks the latest release of a repository when it is first asked for.
     *
     * @param runningVersion the running SC4J version, such as {@code 0.1.3}
     * @param api            the address of the GitHub REST API ({@link ReleaseClient#GITHUB_API})
     * @param repository     the repository whose latest release is checked, or {@code null} for none (the state becomes unknown)
     * @return the created {@link ReleaseStatus} instance
     */
    public static @NotNull ReleaseStatus check(@NotNull ReleaseVersion runningVersion, @NotNull URI api, @Nullable ReleaseRepository repository) {
        return new ReleaseStatus(runningVersion, api, repository, ReleaseState.CHECKING, null);
    }

    /**
     * Creates a {@link ReleaseStatus} instance whose state never changes.
     *
     * @param runningVersion the running SC4J version, such as {@code 0.1.3}
     * @param state          the state
     * @param latestVersion  the latest release version, such as {@code 0.1.4}, or {@code null} if the state does not know it
     * @return the created {@link ReleaseStatus} instance
     * @throws IllegalArgumentException if the state is {@link ReleaseState#CHECKING}, or the latest version is given in a state that does not
     *                                  know it or missing in a state that does
     */
    public static @NotNull ReleaseStatus of(@NotNull ReleaseVersion runningVersion,
                                            @NotNull ReleaseState state, @Nullable ReleaseVersion latestVersion) {
        if (state == ReleaseState.CHECKING || state.isLatestKnown() != (latestVersion != null)) {
            throw Exceptions.newIllegalArgumentException("The release state {0} does not go with the latest version {1}.", state, latestVersion);
        }

        return new ReleaseStatus(runningVersion, null, null, state, latestVersion);
    }


    private final @NotNull ReleaseVersion runningVersion;
    private final @Nullable URI api;
    private final @Nullable ReleaseRepository repository;

    private final @NotNull AtomicBoolean started;
    private volatile @Nullable ReleaseVersion latestVersion;
    private volatile @NotNull ReleaseState state;

    /**
     * Creates a {@link ReleaseStatus} instance.
     *
     * @param runningVersion the running SC4J version
     * @param api            the address of the GitHub REST API, or {@code null} for a status that never changes
     * @param repository     the repository whose latest release is checked, or {@code null} for none
     * @param state          the first state
     * @param latestVersion  the first latest version, or {@code null}
     */
    private ReleaseStatus(@NotNull ReleaseVersion runningVersion, @Nullable URI api, @Nullable ReleaseRepository repository,
                          @NotNull ReleaseState state, @Nullable ReleaseVersion latestVersion) {
        this.runningVersion = runningVersion;
        this.api            = api;
        this.repository     = repository;

        this.started       = new AtomicBoolean(api == null);
        this.latestVersion = latestVersion;
        this.state         = state;
    }

    /**
     * @return the running SC4J version, such as {@code 0.1.3} or {@code 0.1.4-SNAPSHOT}
     */
    public @NotNull ReleaseVersion getRunningVersion() {
        return this.runningVersion;
    }

    /**
     * Returns how the running version stands against the latest release, starting the check at the first call.
     *
     * @return the state
     */
    public @NotNull ReleaseState getState() {
        this.startCheck();

        return this.state;
    }

    /**
     * Returns the version of the latest release, starting the check at the first call. Read after {@link #getState()}, it is known whenever
     * that state knows it ({@link ReleaseState#isLatestKnown()}).
     *
     * @return the version of the latest release, such as {@code 0.1.4}, or {@code null} while it is not known
     */
    public @Nullable ReleaseVersion getLatestVersion() {
        this.startCheck();

        return this.latestVersion;
    }

    private void startCheck() {
        if (!this.started.compareAndSet(false, true)) {
            return;
        }

        URI checkedApi = this.api;
        ReleaseRepository checked = this.repository;

        if (checkedApi == null || checked == null) {
            this.state = ReleaseState.UNKNOWN;

            return;
        }

        Thread thread = new Thread(() -> this.check(checkedApi, checked), CHECK_THREAD_NAME);

        thread.setDaemon(true);
        thread.start();
    }

    private void check(@NotNull URI checkedApi, @NotNull ReleaseRepository checked) {
        try {
            ReleaseCheck check = ReleaseCheck.read(new ReleaseClient(checkedApi), checked, this.runningVersion);
            ReleaseVersion latest = check.getLatestVersion();
            ReleaseState found = check.isNewer() ? ReleaseState.UPDATE_AVAILABLE : ReleaseState.UP_TO_DATE;

            // The latest version is written before the state that makes it known.
            this.latestVersion = latest;
            this.state         = found;
            LOGGER.info("RELEASE: {0}, latest {1} ({2})", this.runningVersion, latest, found);
        } catch (ReleaseException | RuntimeException exception) {
            this.state = ReleaseState.UNKNOWN;
            LOGGER.info("RELEASE: {0}, latest unknown ({1})", this.runningVersion, exception.getMessage());
        }
    }
}
