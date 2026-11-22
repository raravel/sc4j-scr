package org.fogofwarlabs.sc4j.scr.data.format.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandOutput;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Holds the payload codec of every dispatched command type, one codec per type byte: the dispatcher reads its commands with it, the
 * client encodes the commands it submits with it and the replay tools decode recorded commands with it, so a payload layout exists once.
 * <p>
 * Command types without a payload or with the same layout share one codec object ({@link ReplayRawCommand#CODEC},
 * {@link ReplayValueCommand#BYTE} and the like); the target and selection types have their own, which know their type byte.
 */
public final class ReplayCommandCodecs {
    /**
     * The number of command type bytes.
     */
    private static final int TYPE_COUNT = 1 << Byte.SIZE;

    private static final @Nullable ReplayCommandCodec<?> @NotNull [] CODECS = new ReplayCommandCodec<?>[TYPE_COUNT];

    /**
     * The codec of {@link ReplayCommandIds#KEEP_ALIVE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> KEEP_ALIVE = put(ReplayCommandIds.KEEP_ALIVE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#SAVE_GAME}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> SAVE_GAME = put(ReplayCommandIds.SAVE_GAME, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#LOAD_GAME}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> LOAD_GAME = put(ReplayCommandIds.LOAD_GAME, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#RESTART_GAME}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> RESTART_GAME = put(ReplayCommandIds.RESTART_GAME, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#SELECT}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT = putSelection(ReplayCommandIds.SELECT);
    /**
     * The codec of {@link ReplayCommandIds#SELECT_ADD}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT_ADD = putSelection(ReplayCommandIds.SELECT_ADD);
    /**
     * The codec of {@link ReplayCommandIds#SELECT_REMOVE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT_REMOVE = putSelection(ReplayCommandIds.SELECT_REMOVE);
    /**
     * The codec of {@link ReplayCommandIds#BUILD}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayBuildCommand> BUILD = put(ReplayCommandIds.BUILD, ReplayBuildCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#VISION}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> VISION = put(ReplayCommandIds.VISION, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#ALLIANCE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> ALLIANCE = put(ReplayCommandIds.ALLIANCE, ReplayValueCommand.DWORD);
    /**
     * The codec of {@link ReplayCommandIds#GAME_SPEED}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> GAME_SPEED = put(ReplayCommandIds.GAME_SPEED, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#PAUSE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> PAUSE = put(ReplayCommandIds.PAUSE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#RESUME}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> RESUME = put(ReplayCommandIds.RESUME, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#CHEAT}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> CHEAT = put(ReplayCommandIds.CHEAT, ReplayValueCommand.DWORD);
    /**
     * The codec of {@link ReplayCommandIds#HOTKEY}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayHotkeyCommand> HOTKEY = put(ReplayCommandIds.HOTKEY, ReplayHotkeyCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#RIGHT_CLICK}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayTargetCommand> RIGHT_CLICK = putTarget(ReplayCommandIds.RIGHT_CLICK);
    /**
     * The codec of {@link ReplayCommandIds#TARGETED_ORDER}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayTargetCommand> TARGETED_ORDER = putTarget(ReplayCommandIds.TARGETED_ORDER);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_BUILD}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_BUILD = put(ReplayCommandIds.CANCEL_BUILD, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_MORPH}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_MORPH = put(ReplayCommandIds.CANCEL_MORPH, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#STOP}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> STOP = put(ReplayCommandIds.STOP, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#CARRIER_STOP}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CARRIER_STOP = put(ReplayCommandIds.CARRIER_STOP, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#REAVER_STOP}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> REAVER_STOP = put(ReplayCommandIds.REAVER_STOP, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#ORDER_NOTHING}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> ORDER_NOTHING = put(ReplayCommandIds.ORDER_NOTHING, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#RETURN_CARGO}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> RETURN_CARGO = put(ReplayCommandIds.RETURN_CARGO, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#TRAIN}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> TRAIN = put(ReplayCommandIds.TRAIN, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_TRAIN}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> CANCEL_TRAIN = put(ReplayCommandIds.CANCEL_TRAIN, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#CLOAK}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> CLOAK = put(ReplayCommandIds.CLOAK, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#DECLOAK}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> DECLOAK = put(ReplayCommandIds.DECLOAK, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#UNIT_MORPH}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNIT_MORPH = put(ReplayCommandIds.UNIT_MORPH, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#UNSIEGE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNSIEGE = put(ReplayCommandIds.UNSIEGE, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#SIEGE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> SIEGE = put(ReplayCommandIds.SIEGE, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#TRAIN_FIGHTER}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> TRAIN_FIGHTER = put(ReplayCommandIds.TRAIN_FIGHTER, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#UNLOAD_ALL}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNLOAD_ALL = put(ReplayCommandIds.UNLOAD_ALL, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#UNLOAD}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNLOAD = put(ReplayCommandIds.UNLOAD, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#MERGE_ARCHON}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> MERGE_ARCHON = put(ReplayCommandIds.MERGE_ARCHON, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#HOLD_POSITION}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> HOLD_POSITION = put(ReplayCommandIds.HOLD_POSITION, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#BURROW}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> BURROW = put(ReplayCommandIds.BURROW, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#UNBURROW}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNBURROW = put(ReplayCommandIds.UNBURROW, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_NUKE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_NUKE = put(ReplayCommandIds.CANCEL_NUKE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#LIFT_OFF}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayPositionCommand> LIFT_OFF = put(ReplayCommandIds.LIFT_OFF, ReplayPositionCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#TECH}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> TECH = put(ReplayCommandIds.TECH, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_TECH}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_TECH = put(ReplayCommandIds.CANCEL_TECH, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#UPGRADE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UPGRADE = put(ReplayCommandIds.UPGRADE, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_UPGRADE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_UPGRADE = put(ReplayCommandIds.CANCEL_UPGRADE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#CANCEL_ADDON}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> CANCEL_ADDON = put(ReplayCommandIds.CANCEL_ADDON, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#BUILDING_MORPH}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> BUILDING_MORPH = put(ReplayCommandIds.BUILDING_MORPH, ReplayValueCommand.WORD);
    /**
     * The codec of {@link ReplayCommandIds#STIM}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> STIM = put(ReplayCommandIds.STIM, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#SYNC}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> SYNC = put(ReplayCommandIds.SYNC, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#LATENCY}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> LATENCY = put(ReplayCommandIds.LATENCY, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#REPLAY_SPEED}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> REPLAY_SPEED = put(ReplayCommandIds.REPLAY_SPEED, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#LEAVE_GAME}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> LEAVE_GAME = put(ReplayCommandIds.LEAVE_GAME, ReplayValueCommand.BYTE);
    /**
     * The codec of {@link ReplayCommandIds#MINIMAP_PING}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayPositionCommand> MINIMAP_PING = put(ReplayCommandIds.MINIMAP_PING, ReplayPositionCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#MERGE_DARK_ARCHON}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> MERGE_DARK_ARCHON = put(ReplayCommandIds.MERGE_DARK_ARCHON, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#CHAT}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayChatCommand> CHAT = put(ReplayCommandIds.CHAT, ReplayChatCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#REPLAY_SEEK}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> REPLAY_SEEK = put(ReplayCommandIds.REPLAY_SEEK, ReplayValueCommand.DWORD);
    /**
     * The codec of {@link ReplayCommandIds#TURN_RATE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> TURN_RATE = put(ReplayCommandIds.TURN_RATE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#RIGHT_CLICK_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayTargetCommand> RIGHT_CLICK_121 = putTarget(ReplayCommandIds.RIGHT_CLICK_121);
    /**
     * The codec of {@link ReplayCommandIds#TARGETED_ORDER_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayTargetCommand> TARGETED_ORDER_121 = putTarget(ReplayCommandIds.TARGETED_ORDER_121);
    /**
     * The codec of {@link ReplayCommandIds#UNLOAD_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayValueCommand> UNLOAD_121 = put(ReplayCommandIds.UNLOAD_121, ReplayValueCommand.DWORD);
    /**
     * The codec of {@link ReplayCommandIds#SELECT_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT_121 = putSelection(ReplayCommandIds.SELECT_121);
    /**
     * The codec of {@link ReplayCommandIds#SELECT_ADD_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT_ADD_121 = putSelection(ReplayCommandIds.SELECT_ADD_121);
    /**
     * The codec of {@link ReplayCommandIds#SELECT_REMOVE_121}.
     */
    public static final @NotNull ReplayCommandCodec<ReplaySelectionCommand> SELECT_REMOVE_121 = putSelection(ReplayCommandIds.SELECT_REMOVE_121);
    /**
     * The codec of {@link ReplayCommandIds#DYNAMIC_TURN_RATE}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayRawCommand> DYNAMIC_TURN_RATE = put(ReplayCommandIds.DYNAMIC_TURN_RATE, ReplayRawCommand.CODEC);
    /**
     * The codec of {@link ReplayCommandIds#MOD_COMMAND}.
     */
    public static final @NotNull ReplayCommandCodec<ReplayModCommand> MOD_COMMAND = put(ReplayCommandIds.MOD_COMMAND, ReplayModCommand.CODEC);

    /**
     * Finds the codec of a command type byte.
     *
     * @param typeIdentifier the command type byte, any value
     * @return the codec, or {@code null} if the type is not dispatched or the value is no type byte
     */
    public static @Nullable ReplayCommandCodec<?> find(int typeIdentifier) {
        return typeIdentifier >= 0 && typeIdentifier < TYPE_COUNT ? CODECS[typeIdentifier] : null;
    }

    /**
     * Encodes the payload of a command.
     *
     * @param <C>     the command class
     * @param codec   the codec of the command type
     * @param command the command
     * @return the created payload bytes, without the type byte
     */
    public static <C> byte @NotNull [] encode(@NotNull ReplayCommandCodec<C> codec, @NotNull C command) {
        CommandOutput output = new CommandOutput();

        codec.write(command, output);

        return output.toByteArray();
    }

    /**
     * Encodes the payload of a command type whose payload is one value ({@link ReplayValueCommand}), in the width of the type's codec.
     *
     * @param typeIdentifier the command type byte
     * @param value          the value; the bytes above the width are dropped
     * @return the created payload bytes, without the type byte
     * @throws IllegalArgumentException if the payload of the type is not one value
     */
    public static byte @NotNull [] encodeValue(int typeIdentifier, int value) {
        ReplayCommandCodec<?> codec = find(typeIdentifier);
        int width;

        if (codec == ReplayValueCommand.BYTE) {
            width = Byte.BYTES;
        } else if (codec == ReplayValueCommand.WORD) {
            width = Short.BYTES;
        } else if (codec == ReplayValueCommand.DWORD) {
            width = Integer.BYTES;
        } else {
            throw Exceptions.newIllegalArgumentException("The payload of the command type {0} is not one value.", typeIdentifier);
        }

        return encode(ReplayValueCommand.BYTE, new ReplayValueCommand(value, width));
    }

    /**
     * Creates the bytes of a whole command: its type byte, then its payload.
     *
     * @param typeIdentifier the command type byte
     * @param payload        the payload
     * @return the created command bytes
     */
    public static byte @NotNull [] prefixType(int typeIdentifier, byte @NotNull [] payload) {
        byte[] command = new byte[1 + payload.length];

        command[0] = (byte) typeIdentifier;
        System.arraycopy(payload, 0, command, 1, payload.length);

        return command;
    }

    private static <C> @NotNull ReplayCommandCodec<C> put(int typeIdentifier, @NotNull ReplayCommandCodec<C> codec) {
        if (CODECS[typeIdentifier] != null) {
            throw Exceptions.newIllegalStateException("The command type {0} has two codecs.", typeIdentifier);
        }

        CODECS[typeIdentifier] = codec;

        return codec;
    }

    private static @NotNull ReplayCommandCodec<ReplayTargetCommand> putTarget(int typeIdentifier) {
        return put(typeIdentifier, ReplayTargetCommand.createCodec(typeIdentifier));
    }

    private static @NotNull ReplayCommandCodec<ReplaySelectionCommand> putSelection(int typeIdentifier) {
        return put(typeIdentifier, ReplaySelectionCommand.createCodec(typeIdentifier));
    }


    private ReplayCommandCodecs() {
    }
}
