package org.fogofwarlabs.sc4j.scr.game.test;

import org.fogofwarlabs.sc4j.scr.game.extension.ScrGameExtension;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrGamePlugin;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicMemoryView;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.function.Executable;

/**
 * A game extension of the tests, named by the service file of the tests, that creates a plugin only while a thread counts with it
 * ({@link #count}): the plugin observes every write of the EUD address space and reads the memory around each written DWORD. Without counting it
 * creates no plugin, so the other tests run without one.
 */
public final class MemoryReadingPluginSource implements ScrGameExtension {
    /**
     * The bytes read around each written DWORD.
     */
    private static final int READ_LENGTH = 64;
    /**
     * The two halves of the 32-bit address space, each an observed range.
     */
    private static final int HALF_SPACE = Integer.MIN_VALUE;
    private static final int HALF_SPACE_SIZE = Integer.MAX_VALUE;

    /**
     * The write count of the games the current thread creates, or {@code null} while the thread does not count.
     */
    private static final @NotNull ThreadLocal<long @Nullable []> WRITES = new ThreadLocal<>();

    /**
     * Runs an action with this extension's plugin in every game the current thread creates.
     *
     * @param action the action
     * @return the writes the plugins heard
     * @throws Throwable if the action fails
     */
    public static long count(@NotNull Executable action) throws Throwable {
        long[] writes = new long[1];

        WRITES.set(writes);

        try {
            action.execute();
        } finally {
            WRITES.remove();
        }

        return writes[0];
    }

    /**
     * Creates a plugin while the current thread counts.
     *
     * @param game the game, attached and not yet initialized
     * @return the created plugin, or {@code null} while the thread does not count
     */
    @Override
    public @Nullable ScrGamePlugin createPlugin(@NotNull Game game) {
        long[] writes = WRITES.get();

        if (writes == null) {
            return null;
        }

        return new ScrGamePlugin() {
            @Override
            public void onAddressSpaceCreated(@NotNull ClassicMemoryView memory) {
                byte[] bytes = new byte[READ_LENGTH];

                for (int base : new int[]{0, HALF_SPACE}) {
                    memory.observeWrites(base, HALF_SPACE_SIZE, (address, value) -> {
                        writes[0]++;
                        memory.read(address - READ_LENGTH / 2, bytes, 0, READ_LENGTH);
                    });
                }
            }
        };
    }
}
