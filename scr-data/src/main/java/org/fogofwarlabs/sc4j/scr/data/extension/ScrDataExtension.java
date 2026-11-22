package org.fogofwarlabs.sc4j.scr.data.extension;

import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;

/**
 * Extends the data reading of the SC:R mod. A mod built from the SC:R mod's sources adds an implementation with a public no-argument
 * constructor and names it in the resource {@code META-INF/services/org.fogofwarlabs.sc4j.scr.data.extension.ScrDataExtension} of its jar;
 * {@link ScrDataExtensionUtils} reads the named implementations. The SC:R mod itself names none, so its map reader keeps the native limits.
 */
public interface ScrDataExtension {
    /**
     * Returns the largest {@code staredit\scenario.chk} size, in bytes, that the map reader accepts.
     *
     * @return the largest accepted scenario size, at least {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE}
     */
    default int getMaximumScenarioSize() {
        return MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE;
    }
}
