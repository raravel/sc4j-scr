package org.fogofwarlabs.sc4j.scr.game.replay;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.engine.command.CommandRegistry;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.data.format.replay.*;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.frame.GameSpeed;
import org.fogofwarlabs.sc4j.scr.game.order.UnitSelection;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Creates the native command types of one command dispatcher ({@link ReplayCommandExecutor}): an {@link ScrCommandType} for every command type
 * byte the native dispatcher has a case for, with its payload codec ({@link ReplayCommandCodecs}, the one codec of the type) and its case,
 * registered in an engine {@link CommandRegistry} by type byte, and the SC4J mod command ({@link ReplayCommandIds#MOD_COMMAND}), which hands
 * its data to the game's simulation plugins. A type byte without a type is not dispatched.
 */
final class NativeCommandTable {
    /**
     * The number of command type bytes.
     */
    static final int TYPE_COUNT = 1 << Byte.SIZE;

    private static <C> void register(@NotNull CommandRegistry<ScrCommandType<?>> registry,
                                     @NotNull ReplayCommandCodec<C> codec,
                                     ScrCommandType.@NotNull Dispatch dispatch,
                                     ScrCommandType.@NotNull Action<C> action,
                                     int @NotNull ... ids) {
        for (int id : ids) {
            requireOwnCodec(codec, id);
            registry.register(new ScrCommandType<>(id, codec, dispatch, action));
        }
    }

    private static <C> void registerWithIssuer(@NotNull CommandRegistry<ScrCommandType<?>> registry,
                                               @NotNull ReplayCommandCodec<C> codec,
                                               ScrCommandType.@NotNull Dispatch dispatch,
                                               ScrCommandType.@NotNull IssuerAction<C> action,
                                               int id) {
        requireOwnCodec(codec, id);
        registry.register(new ScrCommandType<>(id, codec, dispatch, action));
    }

    private static void requireOwnCodec(@NotNull ReplayCommandCodec<?> codec, int id) {
        if (ReplayCommandCodecs.find(id) != codec) {
            throw Exceptions.newIllegalStateException("The command type {0} is registered with another codec than its own.", id);
        }
    }

    private static void registerTargets(@NotNull CommandRegistry<ScrCommandType<?>> registry,
                                        ScrCommandType.@NotNull Action<ReplayTargetCommand> action,
                                        int @NotNull ... ids) {
        for (int id : ids) {
            ReplayCommandCodec<ReplayTargetCommand> codec = switch (id) {
                case ReplayCommandIds.RIGHT_CLICK -> ReplayCommandCodecs.RIGHT_CLICK;
                case ReplayCommandIds.RIGHT_CLICK_121 -> ReplayCommandCodecs.RIGHT_CLICK_121;
                case ReplayCommandIds.TARGETED_ORDER -> ReplayCommandCodecs.TARGETED_ORDER;
                case ReplayCommandIds.TARGETED_ORDER_121 -> ReplayCommandCodecs.TARGETED_ORDER_121;
                default -> throw Exceptions.newIllegalArgumentException("The command type {0} is no target command.", id);
            };

            register(registry, codec, ScrCommandType.Dispatch.SELECTION, action, id);
        }
    }


    private final @NotNull ReplayCommandExecutor executor;
    private final int instructionLimit;

    /**
     * Creates a {@link NativeCommandTable} instance for one dispatcher.
     *
     * @param executor         the dispatcher whose playback the session cases use
     * @param instructionLimit the instruction budget of each animation script invocation of the dispatcher
     */
    NativeCommandTable(@NotNull ReplayCommandExecutor executor, int instructionLimit) {
        this.executor         = executor;
        this.instructionLimit = instructionLimit;
    }

    /**
     * Creates the command types.
     *
     * @return the created registry, frozen, with {@link #TYPE_COUNT} ids
     */
    @NotNull CommandRegistry<ScrCommandType<?>> createTypes() {
        CommandRegistry<ScrCommandType<?>> registry = new CommandRegistry<>(TYPE_COUNT);

        this.registerSession(registry);
        this.registerPlayer(registry);
        this.registerProduction(registry);
        this.registerUnits(registry);
        this.registerSelection(registry);

        registry.freeze();

        return registry;
    }

    private void registerSession(@NotNull CommandRegistry<ScrCommandType<?>> registry) {
        ReplayCommandCodec<ReplayRawCommand> raw = ReplayRawCommand.CODEC;

        register(registry, raw, ScrCommandType.Dispatch.MARKER, (game, commandPlayer, command) -> {
        }, ReplayCommandIds.KEEP_ALIVE, ReplayCommandIds.SYNC);
        register(registry,
                 raw,
                 ScrCommandType.Dispatch.SESSION,
                 (game, commandPlayer, command) -> this.executor.restartGame(game),
                 ReplayCommandIds.RESTART_GAME);
        register(registry,
                 ReplayValueCommand.DWORD,
                 ScrCommandType.Dispatch.SESSION,
                 (game, commandPlayer, command) -> this.executor.seekReplay(game, command.getValue()),
                 ReplayCommandIds.REPLAY_SEEK);
        // The dispatcher consumes these session commands without a simulation effect.
        register(registry, raw, ScrCommandType.Dispatch.SESSION, (game, commandPlayer, command) -> {
                 }, ReplayCommandIds.SAVE_GAME, ReplayCommandIds.LOAD_GAME, ReplayCommandIds.LATENCY, ReplayCommandIds.REPLAY_SPEED,
                 ReplayCommandIds.TURN_RATE, ReplayCommandIds.DYNAMIC_TURN_RATE);

        register(registry,
                 raw,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> this.executor.pauseOrResume(game, commandPlayer, true),
                 ReplayCommandIds.PAUSE);
        register(registry,
                 raw,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> this.executor.pauseOrResume(game, commandPlayer, false),
                 ReplayCommandIds.RESUME);
        register(registry,
                 ReplayChatCommand.CODEC,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> game.getInterfaceLink().getMessageLines().writeChat(command.getSender(), command.copyText()),
                 ReplayCommandIds.CHAT);
        register(registry, ReplayValueCommand.BYTE, ScrCommandType.Dispatch.SELECTION, (game, commandPlayer, command) -> {
            if (command.getValue() < GameSpeed.SPEED_COUNT) {
                game.setRequestedGameSpeed(command.getValue());
            }
        }, ReplayCommandIds.GAME_SPEED);
        register(registry,
                 ReplayValueCommand.DWORD,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> game.getGameCheats().apply(command.getValue(), commandPlayer, this.instructionLimit),
                 ReplayCommandIds.CHEAT);
        // The minimap ping has no simulation-state effect.
        register(registry, ReplayPositionCommand.CODEC, ScrCommandType.Dispatch.SELECTION, (game, commandPlayer, command) -> {
        }, ReplayCommandIds.MINIMAP_PING);
        // The plugins receive the issuing record, not its command player (a team's leader); a game without simulation plugins runs the mod
        // command without an effect.
        registerWithIssuer(registry, ReplayModCommand.CODEC, ScrCommandType.Dispatch.SESSION, (game, issuer, commandPlayer, command) -> {
            ScrSimulationPlugins simulation = game.getSimulationPlugins();

            if (simulation != null) {
                simulation.runModCommand(issuer, command.copyData());
            }
        }, ReplayCommandIds.MOD_COMMAND);
    }

    private void registerPlayer(@NotNull CommandRegistry<ScrCommandType<?>> registry) {
        register(registry,
                 ReplayValueCommand.WORD,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> game.getPlayerDeparture().setSharedVision(commandPlayer, command.getValue()),
                 ReplayCommandIds.VISION);
        register(registry,
                 ReplayValueCommand.DWORD,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> game.getPlayerDeparture().setAlliances(commandPlayer, command.getValue()),
                 ReplayCommandIds.ALLIANCE);
        // The departure reason is the signed payload byte.
        register(registry,
                 ReplayValueCommand.BYTE,
                 ScrCommandType.Dispatch.SELECTION,
                 (game, commandPlayer, command) -> game.getPlayerDeparture().leave(commandPlayer, (byte) command.getValue(), this.instructionLimit),
                 ReplayCommandIds.LEAVE_GAME);
    }

    private void registerProduction(@NotNull CommandRegistry<ScrCommandType<?>> registry) {
        ReplayCommandCodec<ReplayRawCommand> raw = ReplayRawCommand.CODEC;
        ScrCommandType.Dispatch dispatch = ScrCommandType.Dispatch.SELECTION;

        register(registry, ReplayBuildCommand.CODEC, dispatch, (game, commandPlayer, command) -> {
            game.getUnitCommands().build(commandPlayer, command.getOrder(), command.getUnitType(), command.getTileX(), command.getTileY(),
                                         this.instructionLimit);
        }, ReplayCommandIds.BUILD);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitConstruction().cancelSelected(commandPlayer, false, this.instructionLimit),
                 ReplayCommandIds.CANCEL_BUILD);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitConstruction().cancelSelected(commandPlayer, true, this.instructionLimit),
                 ReplayCommandIds.CANCEL_MORPH);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitResearch().research(commandPlayer, command.getValue(), this.instructionLimit),
                 ReplayCommandIds.TECH);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitResearch().cancelResearch(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.CANCEL_TECH);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitResearch().upgrade(commandPlayer, command.getValue(), this.instructionLimit),
                 ReplayCommandIds.UPGRADE);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitResearch().cancelUpgrade(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.CANCEL_UPGRADE);
        register(registry,
                 ReplayValueCommand.WORD,
                 dispatch,
                 (game, commandPlayer, command) -> game.getProductionQueueCommands().cancelEntry(commandPlayer, command.getValue(),
                                                                                                 this.instructionLimit),
                 ReplayCommandIds.CANCEL_TRAIN);
        register(registry,
                 ReplayValueCommand.WORD,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitProduction().train(commandPlayer, command.getValue()),
                 ReplayCommandIds.TRAIN);
        register(registry,
                 ReplayValueCommand.WORD,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitProduction().morph(commandPlayer, command.getValue(), this.instructionLimit),
                 ReplayCommandIds.UNIT_MORPH);
        register(registry,
                 ReplayValueCommand.WORD,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitProduction().morphBuilding(commandPlayer, command.getValue(), this.instructionLimit),
                 ReplayCommandIds.BUILDING_MORPH);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitAddons().cancelSelected(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.CANCEL_ADDON);
        register(registry,
                 ReplayPositionCommand.CODEC,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitBuildingFlight().liftOff(commandPlayer, command.getX(), command.getY(),
                                                                                        this.instructionLimit),
                 ReplayCommandIds.LIFT_OFF);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitHangar().trainFighter(commandPlayer),
                 ReplayCommandIds.TRAIN_FIGHTER);
    }

    private void registerUnits(@NotNull CommandRegistry<ScrCommandType<?>> registry) {
        ReplayCommandCodec<ReplayRawCommand> raw = ReplayRawCommand.CODEC;
        ScrCommandType.Dispatch dispatch = ScrCommandType.Dispatch.SELECTION;

        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitCommands().orderNothing(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.ORDER_NOTHING);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getArchonOrders().merge(commandPlayer, false, this.instructionLimit),
                 ReplayCommandIds.MERGE_ARCHON);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getArchonOrders().merge(commandPlayer, true, this.instructionLimit),
                 ReplayCommandIds.MERGE_DARK_ARCHON);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().cancelNuke(commandPlayer),
                 ReplayCommandIds.CANCEL_NUKE);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().cloak(commandPlayer, true),
                 ReplayCommandIds.CLOAK);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().cloak(commandPlayer, false),
                 ReplayCommandIds.DECLOAK);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().siege(commandPlayer, true, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.SIEGE);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().siege(commandPlayer, false, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.UNSIEGE);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitBurrowing().burrow(commandPlayer, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.BURROW);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitBurrowing().unburrow(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.UNBURROW);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTechnology().stimPack(commandPlayer, this.instructionLimit),
                 ReplayCommandIds.STIM);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitTransport().unloadAll(commandPlayer, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.UNLOAD_ALL);

        ScrCommandType.Action<ReplayValueCommand> unload = (game, commandPlayer, command) ->
                game.getUnitTransport().unload(commandPlayer,
                                               game.getUnitSystem().requireUnitPool().resolveHandle(command.getValue()),
                                               this.instructionLimit);

        register(registry, ReplayValueCommand.WORD, dispatch, unload, ReplayCommandIds.UNLOAD);
        register(registry, ReplayValueCommand.DWORD, dispatch, unload, ReplayCommandIds.UNLOAD_121);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitHangar().stop(commandPlayer, ScrOrderIds.CARRIER_STOP, this.instructionLimit),
                 ReplayCommandIds.CARRIER_STOP);
        register(registry,
                 raw,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitHangar().stop(commandPlayer, ScrOrderIds.REAVER_STOP, this.instructionLimit),
                 ReplayCommandIds.REAVER_STOP);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitCommands().returnCargo(commandPlayer, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.RETURN_CARGO);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitCommands().holdPosition(commandPlayer, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.HOLD_POSITION);
        register(registry,
                 ReplayValueCommand.BYTE,
                 dispatch,
                 (game, commandPlayer, command) -> game.getUnitCommands().stop(commandPlayer, command.isSet(), this.instructionLimit),
                 ReplayCommandIds.STOP);
        registerTargets(registry, (game, commandPlayer, command) ->
                                game.getUnitCommands().order(commandPlayer,
                                                             command.getOrderIdentifier(),
                                                             command.getX(), command.getY(),
                                                             game.getUnitSystem().requireUnitPool().resolveHandle((int) command.getTargetHandle()),
                                                             command.getUnitTypeIdentifier(),
                                                             command.getQueueFlags() != 0,
                                                             this.instructionLimit),
                        ReplayCommandIds.TARGETED_ORDER, ReplayCommandIds.TARGETED_ORDER_121);
        registerTargets(registry, (game, commandPlayer, command) ->
                                game.getUnitCommands().rightClick(commandPlayer,
                                                                  command.getX(), command.getY(),
                                                                  game.getUnitSystem().requireUnitPool().resolveHandle((int) command.getTargetHandle()),
                                                                  command.getUnitTypeIdentifier(),
                                                                  command.getQueueFlags() != 0,
                                                                  this.instructionLimit),
                        ReplayCommandIds.RIGHT_CLICK, ReplayCommandIds.RIGHT_CLICK_121);
    }

    private void registerSelection(@NotNull CommandRegistry<ScrCommandType<?>> registry) {
        register(registry, ReplayHotkeyCommand.CODEC, ScrCommandType.Dispatch.SELECTION, (game, commandPlayer, command) -> {
            UnitSelection selection = game.requireSelection();

            selection.executeControlGroup(game, commandPlayer, command.getGroup(), command.getOperation());
        }, ReplayCommandIds.HOTKEY);

        List<ReplayCommandCodec<ReplaySelectionCommand>> codecs = List.of(ReplayCommandCodecs.SELECT, ReplayCommandCodecs.SELECT_ADD,
                                                                          ReplayCommandCodecs.SELECT_REMOVE, ReplayCommandCodecs.SELECT_121,
                                                                          ReplayCommandCodecs.SELECT_ADD_121, ReplayCommandCodecs.SELECT_REMOVE_121);
        int[] ids = {ReplayCommandIds.SELECT, ReplayCommandIds.SELECT_ADD, ReplayCommandIds.SELECT_REMOVE,
                     ReplayCommandIds.SELECT_121, ReplayCommandIds.SELECT_ADD_121, ReplayCommandIds.SELECT_REMOVE_121};

        for (int index = 0; index < ids.length; index++) {
            register(registry, codecs.get(index), ScrCommandType.Dispatch.SELECTION, (game, commandPlayer, command) -> {
                UnitSelection selection = game.requireSelection();

                selection.execute(game, command, commandPlayer);
            }, ids[index]);
        }
    }
}
