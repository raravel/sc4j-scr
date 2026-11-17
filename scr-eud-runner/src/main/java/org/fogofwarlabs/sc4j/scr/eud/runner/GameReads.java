package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicAddressSpace;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads game state through the classic EUD address space, as a map's own triggers read it.
 */
final class GameReads {
    /**
     * The death table: 228 units of 12 players.
     */
    static final int DEATHS_ADDRESS = 0x58A364;
    /**
     * The unit table: 1700 entries of 336 bytes; an entry with a sprite (offset 0x0C) is a live unit.
     */
    static final int UNIT_TABLE_ADDRESS = 0x59CCA8;
    static final int UNIT_ENTRY_SIZE = 336;
    static final int UNIT_ENTRIES = 1700;
    static final int UNIT_SPRITE_OFFSET = 0x0C;
    /**
     * The chat and game message lines: 11 lines of 218 bytes (0x640B58 before them is the next-line cursor).
     */
    static final int MESSAGE_LINES_ADDRESS = 0x640B60;
    static final int MESSAGE_LINE_COUNT = 11;
    static final int MESSAGE_LINE_SIZE = 218;

    private final @NotNull ClassicAddressSpace space;

    GameReads(@NotNull Game game) {
        this.space = liveSpace(game);
    }

    /**
     * @param game the game
     * @return the address space the map's triggers use, so that what they wrote is read back; a new one before the first EUD access
     */
    static @NotNull ClassicAddressSpace liveSpace(@NotNull Game game) {
        ClassicAddressSpace live = game.getTriggerContext() == null ? null : game.getTriggerContext().getClassicAddressSpace();

        return live != null ? live : ClassicAddressSpace.create(game);
    }

    /**
     * @param player the 0-based player
     * @param unit   the unit
     * @return the signed death count
     */
    int deaths(int player, int unit) {
        return this.space.readDword(DEATHS_ADDRESS + (unit * 12 + player) * 4);
    }

    /**
     * @param address a 4-byte aligned address
     * @return the unsigned DWORD
     */
    long memory(int address) {
        return Integer.toUnsignedLong(this.space.readDword(address));
    }

    /**
     * @return the units with a sprite
     */
    int liveUnits() {
        int units = 0;

        for (int index = 0; index < UNIT_ENTRIES; index++) {
            if (this.space.readDword(UNIT_TABLE_ADDRESS + index * UNIT_ENTRY_SIZE + UNIT_SPRITE_OFFSET) != 0) {
                units++;
            }
        }

        return units;
    }

    /**
     * @return the nonempty message lines, top to bottom, without colour and control codes
     */
    @NotNull List<String> messageLines() {
        List<String> lines = new ArrayList<>();

        for (int line = 0; line < MESSAGE_LINE_COUNT; line++) {
            int start = MESSAGE_LINES_ADDRESS + line * MESSAGE_LINE_SIZE;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();

            for (int offset = 0; offset < MESSAGE_LINE_SIZE; offset++) {
                int value = this.readByte(start + offset);

                if (value == 0) {
                    break;
                }

                if (value >= 0x20) {
                    bytes.write(value);
                }
            }

            String text = bytes.toString(StandardCharsets.UTF_8).strip();

            if (!text.isEmpty()) {
                lines.add(text);
            }
        }

        return lines;
    }

    private int readByte(int address) {
        int aligned = address & ~3;

        return this.space.readDword(aligned) >>> ((address - aligned) * 8) & 0xFF;
    }
}
