package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Reads the epScript trace stack of a map built with {@code [main] debug}. eudplib writes the trace file {@code <output>.epmap}: the
 * header {@code H1} that the map writes at runtime in front of its trace stack, and one {@code - <ID> : <file>|<function>|<line>} entry
 * per traced statement. The stack holds one DWORD per call depth after the 16-byte header: the ID of the statement running at that depth,
 * 0 at a depth that logged none and above the top.
 */
final class TraceReader {
    private static final int HEADER_SIZE = 16;
    private static final int STACK_SIZE = 2048;

    private final byte @NotNull [] header;
    private final @NotNull Map<Integer, String> locations;
    private int stackAddress;

    private TraceReader(byte @NotNull [] header, @NotNull Map<Integer, String> locations) {
        this.header    = header;
        this.locations = locations;
    }

    /**
     * Reads a trace file.
     *
     * @param path the {@code .epmap} file
     * @return the reader
     * @throws IOException              if the file cannot be read
     * @throws IllegalArgumentException if the file has no {@code H1} header
     */
    static @NotNull TraceReader load(@NotNull Path path) throws IOException {
        byte[] header = null;
        Map<Integer, String> locations = new HashMap<>();

        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String text = line.strip();

            if (text.startsWith("H1:")) {
                header = HexFormat.of().parseHex(text.substring(3).strip());
            } else if (text.startsWith("- ")) {
                int separator = text.indexOf(" : ");

                if (separator > 2) {
                    locations.put(Integer.parseUnsignedInt(text.substring(2, separator).strip(), 16), text.substring(separator + 3));
                }
            }
        }

        if (header == null || header.length != HEADER_SIZE) {
            throw new IllegalArgumentException("The trace file has no 16-byte H1 header: " + path);
        }

        return new TraceReader(header, locations);
    }

    /**
     * @return the number of traced statements
     */
    int size() {
        return this.locations.size();
    }

    /**
     * Reads the running statements, outermost first.
     *
     * @param game the game
     * @return the locations as {@code file|function|line}, empty when the stack is empty or cannot be found
     */
    @NotNull List<String> readStack(@NotNull Game game) {
        ClassicAddressSpace space = GameReads.liveSpace(game);

        if (this.stackAddress == 0) {
            this.stackAddress = this.findStack(space);

            if (this.stackAddress == 0) {
                return List.of();
            }
        }

        List<String> stack = new ArrayList<>();

        // A call depth that logged no statement yet holds 0 (the outermost one, for one); leaving a call clears its DWORD, so every
        // DWORD above the top is 0. The nonzero DWORDs in depth order are the running statements.
        for (int depth = 0; depth < STACK_SIZE; depth++) {
            int identifier = space.readDword(this.stackAddress + depth * Integer.BYTES);

            if (identifier != 0) {
                stack.add(this.locate(identifier));
            }
        }

        return stack;
    }

    /**
     * @param identifier a trace ID
     * @return its location, or the ID in hexadecimal when the trace file does not list it
     */
    @NotNull String locate(int identifier) {
        String location = this.locations.get(identifier);

        return location != null ? location : String.format("unknown trace id %08X", identifier);
    }

    /**
     * @return the address of the first stack DWORD, or 0 when no region holds the header
     */
    private int findStack(@NotNull ClassicAddressSpace space) {
        for (ClassicRegion region : space.getRegions()) {
            int size = region.getSize();


            if (size < HEADER_SIZE + Integer.BYTES || size > 64 << 20) {
                continue;
            }

            byte[] bytes = new byte[size];

            try {
                region.read(0, bytes, 0, size);
            } catch (RuntimeException unreadable) {
                continue;
            }

            int offset = indexOf(bytes, this.header);

            if (offset >= 0) {
                return region.getBase() + offset + HEADER_SIZE;
            }
        }

        return 0;
    }

    private static int indexOf(byte @NotNull [] bytes, byte @NotNull [] pattern) {
        outer:
        for (int start = 0; start + pattern.length <= bytes.length; start += Integer.BYTES) {
            for (int index = 0; index < pattern.length; index++) {
                if (bytes[start + index] != pattern[index]) {
                    continue outer;
                }
            }

            return start;
        }

        return -1;
    }

    /**
     * @param stack a stack from {@link #readStack}
     * @return the innermost location, or {@code null}
     */
    static @Nullable String top(@NotNull List<String> stack) {
        return stack.isEmpty() ? null : stack.getLast();
    }
}
