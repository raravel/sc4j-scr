package org.fogofwarlabs.sc4j.screnhanced.script.sync;

import org.jetbrains.annotations.NotNull;

/**
 * A whole value of a player's script commands, joined from its mod commands ({@link ScriptCommands}).
 */
public final class ScriptCommandMessage {
    private final int player;
    private final byte @NotNull [] value;

    /**
     * Creates a {@link ScriptCommandMessage} instance.
     *
     * @param player the slot id of the sending player
     * @param value  the encoded value ({@link ScriptValueUtils})
     */
    ScriptCommandMessage(int player, byte @NotNull [] value) {
        this.player = player;
        this.value  = value;
    }

    /**
     * @return the slot id of the sending player
     */
    public int getPlayer() {
        return this.player;
    }

    /**
     * @return the encoded value ({@link ScriptValueUtils}); the array is shared and must not be changed
     */
    public byte @NotNull [] getValue() {
        return this.value;
    }
}
