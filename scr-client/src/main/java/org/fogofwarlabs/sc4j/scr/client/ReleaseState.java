package org.fogofwarlabs.sc4j.scr.client;

/**
 * Lists how the running SC4J version stands against the latest SC4J release.
 */
public enum ReleaseState {
    /**
     * The latest release is being read.
     */
    CHECKING,
    /**
     * No release is newer than the running version.
     */
    UP_TO_DATE,
    /**
     * A release is newer than the running version.
     */
    UPDATE_AVAILABLE,
    /**
     * The latest release is not known: the read failed (no network, no answer, no release).
     */
    UNKNOWN;

    /**
     * @return {@code true} if the latest release is known in this state, {@code false} otherwise
     */
    public boolean isLatestKnown() {
        return this == UP_TO_DATE || this == UPDATE_AVAILABLE;
    }
}
