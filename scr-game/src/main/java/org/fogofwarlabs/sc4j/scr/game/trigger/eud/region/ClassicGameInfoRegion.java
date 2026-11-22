package org.fogofwarlabs.sc4j.scr.game.trigger.eud.region;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplayHeaderBuilder;
import org.fogofwarlabs.sc4j.scr.game.trigger.eud.ClassicRegion;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * The live game-info block: the game header section from its game settings up to the player records. The simulation
 * reads the game type and subtype WORDs and the option template (victory, starting units, allies, teams for shared
 * control, cheats); this emulation decodes them once into immutable settings, so a write that changes one of those bytes
 * stops explicitly. The other bytes (game name, map size, map name, ...) keep what EUD writes.
 */
public final class ClassicGameInfoRegion implements ClassicRegion {
    /**
     * The classic address of the block.
     */
    public static final int BASE = 0x5967F8;

    /**
     * The offset of the block in the game header.
     */
    private static final int HEADER_OFFSET = 0x14;
    /**
     * The size of the block, up to the player records of the game header.
     */
    private static final int SIZE = ReplayHeaderBuilder.PLAYERS_OFFSET - HEADER_OFFSET;
    /**
     * The offset of the game type WORD.
     */
    private static final int GAME_TYPE = ReplayHeaderBuilder.GAME_TYPE_OFFSET - HEADER_OFFSET;
    /**
     * The offset of the game subtype WORD, which follows the game type WORD.
     */
    private static final int SUBTYPE = ReplayHeaderBuilder.GAME_SUBTYPE_OFFSET - HEADER_OFFSET;
    /**
     * The offset of the option template, which runs to the end of the block.
     */
    private static final int TEMPLATE = 0x6D;


    private final byte @NotNull [] bytes;


    /**
     * Creates a {@link ClassicGameInfoRegion} instance over a copy of the game header's block.
     *
     * @param header the game header section ({@link ReplayHeaderBuilder#HEADER_SIZE} bytes)
     */
    public ClassicGameInfoRegion(byte @NotNull [] header) {
        this.bytes = Arrays.copyOfRange(header, HEADER_OFFSET, HEADER_OFFSET + SIZE);
    }


    /**
     * @return the classic base address
     */
    @Override
    public int getBase() {
        return BASE;
    }

    /**
     * @return the size in bytes
     */
    @Override
    public int getSize() {
        return SIZE;
    }


    /**
     * Reads the unsigned byte at an offset.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @return the unsigned byte at the offset
     */
    @Override
    public int readByte(int offset) {
        return this.bytes[offset] & BYTE_MASK;
    }

    /**
     * Stores one byte, changing the domain state that holds it.
     *
     * @param offset the byte offset, below {@link #getSize()}
     * @param value  the byte value in the low eight bits
     * @throws UnsupportedOperationException if the write changes a game setting byte that the simulation reads
     */
    @Override
    public void writeByte(int offset, int value) {
        boolean simulated = offset >= GAME_TYPE && offset < SUBTYPE + Short.BYTES || offset >= TEMPLATE;

        if (simulated && (this.bytes[offset] & BYTE_MASK) != (value & BYTE_MASK)) {
            throw Exceptions.newUnsupportedOperationException(
                    "EUD write {0} to {1} changes the game settings that the simulation reads.",
                    String.format("0x%02X", value & BYTE_MASK),
                    String.format("0x%08X", BASE + offset)
            );
        }

        this.bytes[offset] = (byte) value;
    }
}
