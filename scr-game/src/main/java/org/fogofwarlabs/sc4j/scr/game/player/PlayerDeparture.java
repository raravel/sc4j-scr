package org.fogofwarlabs.sc4j.scr.game.player;

import org.fogofwarlabs.sc4j.base.exception.Exceptions;
import org.fogofwarlabs.sc4j.scr.content.ScrOrderIds;
import org.fogofwarlabs.sc4j.scr.core.GameSettings;
import org.fogofwarlabs.sc4j.scr.data.format.replay.ReplaySettings;
import org.fogofwarlabs.sc4j.scr.data.type.ScrUnitTypeView;
import org.fogofwarlabs.sc4j.scr.data.type.constant.UnitTypeFlags;
import org.fogofwarlabs.sc4j.scr.game.extension.ScrSimulationPlugins;
import org.fogofwarlabs.sc4j.scr.game.sprite.SelectionImages;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.VictoryCondition;
import org.fogofwarlabs.sc4j.scr.game.trigger.TriggerContext;
import org.fogofwarlabs.sc4j.scr.game.unit.Unit;
import org.fogofwarlabs.sc4j.scr.game.unit.UnitPool;
import org.fogofwarlabs.sc4j.scr.game.visibility.LocalVision;
import org.fogofwarlabs.sc4j.scr.game.visibility.SightCoverage;
import org.jetbrains.annotations.NotNull;

/**
 * Applies the player-scoped commands that change allegiance, vision or player presence.
 * <p>
 * Alliance changes clear targets that become friendly, vision changes update relations and sight, and departures record the outcome before removing
 * or neutralizing units.
 */
public final class PlayerDeparture {
    /**
     * The leave reason of a dropped player; any other reason is a departure.
     */
    public static final int DROP_REASON = 6;

    /**
     * The bits of one player's alliance value in the recorded alliance DWORD.
     */
    private static final int ALLIANCE_BITS = 2;
    /**
     * The mask of one player's alliance value.
     */
    private static final int ALLIANCE_MASK = 3;
    /**
     * The shared-vision bits a vision command sets (the low byte, players {@code 0..7}).
     */
    private static final int SHARED_VISION_BITS = 0xFF;
    /**
     * The shared-vision bits a vision command keeps (the slots past the low byte).
     */
    private static final int KEPT_VISION_BITS = 0xFF00;

    private final @NotNull Game game;

    /**
     * Creates a {@link PlayerDeparture} instance for one game.
     *
     * @param game the game this system belongs to
     */
    public PlayerDeparture(@NotNull Game game) {
        this.game = game;
    }

    /**
     * Sets a player's alliance row, rebuilds the local relation table with the selection circles ({@link SelectionImages#updateLocalRelations}) and
     * drops enemy-order targets that became allies.
     * <p>
     * The recorded DWORD holds two bits per player, player {@code 0} in the lowest bits. The command changes nothing unless the game options allow
     * alliances ({@link PlayerRelations#areAlliancesAllowed(int, int)}), in replay playback too.
     *
     * @param owner  the issuing player
     * @param packed the recorded alliance DWORD
     * @throws UnsupportedOperationException if player relations are unavailable or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void setAlliances(int owner, int packed) {
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();

        if (relations == null) {
            throw Exceptions.newUnsupportedOperationException("Alliance commands require player relations.");
        }

        if (!this.areAlliancesAllowed()) {
            return;
        }

        int[] alliances = new int[PlayerTable.PLAYER_SLOTS];

        for (int player = 0; player < PlayerTable.PLAYER_SLOTS; player++) {
            alliances[player] = (packed >>> (player * ALLIANCE_BITS)) & ALLIANCE_MASK;
        }

        relations.setAlliances(owner, alliances);
        this.game.getSelectionImages().updateLocalRelations();

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();

        for (int index = pool.getFirstOwnerIndex(owner); index != 0; index = pool.getNextOwnerIndex(index)) {
            Unit unit = pool.getUnit(index);
            Unit target = unit.getOrderTargetUnit();

            if (target == null || this.game.requireGameData().getOrderTypes().getUseWeaponTargeting(unit.getPrimaryOrder()) == 0) {
                continue;
            }

            if (alliances[target.getOwnerIndex()] != 0 && !this.game.getUnitTargeting().isEnemy(unit, target)) {
                unit.setOrderTargetUnit(null);
            }
        }
    }

    /**
     * Sets the issuer's shared vision from a vision command: the low byte of the issuer's shared-vision mask becomes the recorded byte with the
     * issuer's own bit always set, and bits {@code 8..15} are kept.
     * <p>
     * The command changes nothing when a player of the recorded byte, tested from player {@code 7} down to {@code 0}, may not receive the issuer's
     * vision ({@link #canShareVision(int, int)}).
     *
     * @param owner the issuing player
     * @param flags the recorded vision WORD
     * @throws UnsupportedOperationException if relations or sight coverage are unavailable
     */
    public void setSharedVision(int owner, int flags) {
        PlayerRelations relations = this.game.getPlayerTable().getPlayerRelations();
        SightCoverage sight = this.game.getSightCoverage();

        if (relations == null || sight == null) {
            throw Exceptions.newUnsupportedOperationException("Vision commands require player relations and sight coverage.");
        }

        for (int player = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; player >= 0; player--) {
            if ((flags & 1 << player) != 0 && !this.canShareVision(owner, player)) {
                return;
            }
        }

        int mask = flags & SHARED_VISION_BITS | 1 << owner | relations.getSharedVision(owner) & KEPT_VISION_BITS;

        relations.setSharedVision(owner, mask);
        sight.setSharedVisionMask(owner, mask);
    }

    /**
     * Tests whether a player may receive another player's vision, for the vision command and the alliance dialog.
     * <p>
     * Every player may in replay playback. Otherwise an observer shares with nobody, an observer receives from everybody, and other players receive
     * when the game options allow alliances ({@link PlayerRelations#areAlliancesAllowed(int, int)}).
     *
     * @param owner  the player that shares its vision
     * @param player the player that receives it
     * @return {@code true} if the player may receive the owner's vision, {@code false} otherwise
     */
    public boolean canShareVision(int owner, int player) {
        if (this.game.isReplayPlayback()) {
            return true;
        }

        Player issuer = this.game.getPlayerTable().getPlayer(owner);
        Player receiver = this.game.getPlayerTable().getPlayer(player);

        if (issuer != null && issuer.getType() == PlayerTypes.OBSERVER) {
            return false;
        }

        if (receiver != null && receiver.getType() == PlayerTypes.OBSERVER) {
            return true;
        }

        return this.areAlliancesAllowed();
    }

    /**
     * Tests the game options of the alliance and vision commands: a game without recorded options allows no alliances.
     *
     * @return {@code true} if the game options allow alliances, {@code false} otherwise
     */
    private boolean areAlliancesAllowed() {
        return this.game.getGameSettings() instanceof ReplaySettings settings &&
               PlayerRelations.areAlliancesAllowed(settings.getAlliesEnabled(), settings.getTournamentMode());
    }


    /**
     * Applies a leave command: the drop reason sets {@link Player#VICTORY_STATE_DROPPED}, any other reason (a departure)
     * {@link Player#VICTORY_STATE_DEFEATED}. The departure message enters the message lines and the player's running triggers are discarded; a player
     * that already has a victory state keeps its units and controller.
     *
     * @param owner            the leaving player
     * @param reason           the signed recorded reason byte
     * @param instructionLimit the order and animation script budget
     * @throws UnsupportedOperationException if the player record is unavailable
     */
    public void leave(int owner, int reason, int instructionLimit) {
        Player player = this.game.getPlayerTable().getPlayer(owner);

        if (player == null) {
            throw Exceptions.newUnsupportedOperationException("Leaving requires the player record.");
        }

        this.game.getInterfaceLink().getMessageLines().writeDeparture(owner, reason == DROP_REASON);

        ScrSimulationPlugins simulation = this.game.getSimulationPlugins();

        if (simulation != null) {
            simulation.reportPlayerLeft(owner, reason);
        }

        TriggerContext triggers = this.game.getTriggerContext();

        if (triggers != null) {
            triggers.clearPlayerRecords(owner);
        }

        if (player.getVictoryState() != 0) {
            return;
        }

        player.setVictoryState(reason == DROP_REASON ? Player.VICTORY_STATE_DROPPED : Player.VICTORY_STATE_DEFEATED);

        if (simulation != null) {
            simulation.reportPlayerResult(owner, player.getVictoryState());
        }

        this.removePlayer(owner, instructionLimit);
    }

    /**
     * Removes a player from play.
     * <p>
     * A computer becomes {@link PlayerTypes#COMPUTER_LEFT} and a human {@link PlayerTypes#PLAYER_LEFT}. A team game changes the player types of the
     * whole force. When the victory condition kills departing units ({@link VictoryCondition#killsDepartingUnits()}), every unit of the owner list
     * that is no subunit and neither removed nor dying is killed, and a powerup a worker carries is dropped by that worker instead. Otherwise every
     * unit of the owner list is made neutral. Then the player leaves the vision of a replay or observer view ({@link LocalVision#removePlayer}). The
     * display refresh and the session notification of a departed human are not represented.
     *
     * @param owner            the removed player
     * @param instructionLimit the order and animation script budget
     * @throws UnsupportedOperationException if the game options are not established or the unit pool is not initialized
     * @throws NullPointerException          if the world was built without game data
     */
    public void removePlayer(int owner, int instructionLimit) {
        GameSettings settings = this.game.getGameSettings();

        if (settings == null) {
            throw Exceptions.newUnsupportedOperationException("Removing a player requires the established game options.");
        }

        Player player = this.game.getPlayerTable().requirePlayer(owner);

        if (settings instanceof ReplaySettings replaySettings && replaySettings.getTeamsEnabled() != 0) {
            // The team branch: every slot of the owner's force leaves, from the last playable slot down.
            for (int slot = PlayerTable.PLAYABLE_PLAYER_SLOTS - 1; slot >= 0; slot--) {
                Player member = this.game.getPlayerTable().requirePlayer(slot);

                if (member.getTeam() != player.getTeam()) {
                    continue;
                }

                if (member.getType() == PlayerTypes.COMPUTER) {
                    member.setType(PlayerTypes.COMPUTER_LEFT);
                } else if (member.getType() == PlayerTypes.HUMAN) {
                    member.setType(PlayerTypes.PLAYER_LEFT);
                }
            }
        } else if (player.getType() == PlayerTypes.COMPUTER) {
            player.setType(PlayerTypes.COMPUTER_LEFT);
        } else if (player.getType() == PlayerTypes.HUMAN) {
            player.setType(PlayerTypes.PLAYER_LEFT);
        }

        UnitPool pool = this.game.getUnitSystem().requireUnitPool();
        int index = pool.getFirstOwnerIndex(owner);

        if (VictoryCondition.of(settings.getVictoryCondition()).killsDepartingUnits()) {
            ScrUnitTypeView units = this.game.requireGameData().getUnitTypes();

            while (index != 0) {
                Unit unit = pool.getUnit(index);
                int next = pool.getNextOwnerIndex(index);
                int flags = units.getFlags(unit.getUnitId());

                if ((flags & UnitTypeFlags.SUBUNIT) == 0 && unit.getSpriteOrNull() != null && unit.getPrimaryOrder() != ScrOrderIds.DIE) {
                    Unit carrier = (flags & UnitTypeFlags.POWERUP) != 0 ? unit.getPowerupCarrier() : null;

                    if (carrier != null) {
                        this.game.getUnitPowerups().release(carrier, instructionLimit);
                    } else {
                        this.game.getUnitLifecycle().kill(unit, instructionLimit);
                    }
                }

                index = next;
            }
        } else {
            while (index != 0) {
                int next = pool.getNextOwnerIndex(index);

                this.game.getUnitOwnership().makeNeutral(pool.getUnit(index), instructionLimit);
                index = next;
            }
        }

        this.game.getLocalVision().removePlayer(owner);
    }
}
