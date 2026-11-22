package org.fogofwarlabs.sc4j.scr.data.extension;

import org.fogofwarlabs.sc4j.scr.data.format.mpq.MpqArchive;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Reads the {@link ScrDataExtension} implementations that the jar of the running mod names. They are found once per class loader, with the
 * class loader of {@link ScrDataExtension}, so a mod jar in its own class loader finds its own implementations.
 */
public final class ScrDataExtensionUtils {
    private static final @NotNull List<@NotNull ScrDataExtension> EXTENSIONS = ServiceLoader.load(ScrDataExtension.class, ScrDataExtension.class.getClassLoader())
                                                                                            .stream()
                                                                                            .map(ServiceLoader.Provider::get)
                                                                                            .toList();

    private static final int MAXIMUM_SCENARIO_SIZE = findMaximumScenarioSize(EXTENSIONS);

    /**
     * Returns the largest {@code staredit\scenario.chk} size that the map reader accepts: the largest one an extension returns
     * ({@link ScrDataExtension#getMaximumScenarioSize}), or {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE} without an extension.
     *
     * @return the largest accepted scenario size in bytes
     */
    public static int getMaximumScenarioSize() {
        return MAXIMUM_SCENARIO_SIZE;
    }

    /**
     * Finds the largest scenario size that extensions accept.
     *
     * @param extensions the extensions
     * @return the largest size an extension returns, at least {@link MpqArchive#NATIVE_MAXIMUM_SCENARIO_SIZE}
     */
    static int findMaximumScenarioSize(@NotNull List<@NotNull ScrDataExtension> extensions) {
        int maximumSize = MpqArchive.NATIVE_MAXIMUM_SCENARIO_SIZE;

        for (ScrDataExtension extension : extensions) {
            maximumSize = Math.max(maximumSize, extension.getMaximumScenarioSize());
        }

        return maximumSize;
    }

    private ScrDataExtensionUtils() {
    }
}
