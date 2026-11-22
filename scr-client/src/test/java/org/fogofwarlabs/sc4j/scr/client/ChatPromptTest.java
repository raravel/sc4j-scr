package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.client.input.KeyModifier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Checks the chat box mode selected when it opens and the route of a typed line.
 */
final class ChatPromptTest {
    private static final int SHIFT = KeyModifier.SHIFT.getMask();
    private static final int CONTROL = KeyModifier.CONTROL.getMask();
    private static final int ALT = KeyModifier.ALT.getMask();
    private static final int NO_MODIFIERS = 0;

    /**
     * The recipient byte for chat to every player.
     */
    private static final int RECIPIENT_ALL = 8;
    /**
     * The recipient byte for chat to the allies.
     */
    private static final int RECIPIENT_ALLIES = 9;
    /**
     * A recipient byte that selects one player's slot.
     */
    private static final int RECIPIENT_PLAYER = 3;
    /**
     * Another recipient byte that selects one player's slot.
     */
    private static final int RECIPIENT_OTHER_PLAYER = 2;

    /**
     * The chat modes: single player, every player, the allies, the chosen recipient and the observers.
     */
    private static final int MODE_SINGLE_PLAYER = 1;
    private static final int MODE_ALL = 2;
    private static final int MODE_ALLIES = 3;
    private static final int MODE_RECIPIENT = 4;
    private static final int MODE_OBSERVERS = 5;

    @Test
    void choose_mode_should_return_single_player_mode_in_single_player_game() {
        Assertions.assertEquals(MODE_SINGLE_PLAYER, ChatPrompt.chooseMode(false, false, SHIFT, true, RECIPIENT_ALLIES));
    }

    @Test
    void choose_mode_should_return_observers_mode_for_observer() {
        Assertions.assertEquals(MODE_OBSERVERS, ChatPrompt.chooseMode(true, true, NO_MODIFIERS, true, RECIPIENT_ALL));
    }

    @Test
    void choose_mode_should_send_to_everyone_with_shift_or_control() {
        Assertions.assertEquals(MODE_ALL, ChatPrompt.chooseMode(true, false, SHIFT, true, RECIPIENT_ALLIES));
        Assertions.assertEquals(MODE_ALL, ChatPrompt.chooseMode(true, false, CONTROL, true, RECIPIENT_PLAYER));
    }

    @Test
    void choose_mode_should_send_alt_to_allies_only_when_alliances_are_allowed() {
        Assertions.assertEquals(MODE_ALLIES, ChatPrompt.chooseMode(true, false, ALT, true, RECIPIENT_ALL));
        Assertions.assertEquals(MODE_ALL, ChatPrompt.chooseMode(true, false, ALT, false, RECIPIENT_ALL));
    }

    @Test
    void choose_mode_should_follow_recipient_byte_without_modifiers() {
        Assertions.assertEquals(MODE_ALL, ChatPrompt.chooseMode(true, false, NO_MODIFIERS, true, RECIPIENT_ALL));
        Assertions.assertEquals(MODE_ALLIES, ChatPrompt.chooseMode(true, false, NO_MODIFIERS, true, RECIPIENT_ALLIES));

        // Allies without alliances, and a player's slot, open the recipient's label ("To %s:").
        Assertions.assertEquals(MODE_RECIPIENT, ChatPrompt.chooseMode(true, false, NO_MODIFIERS, false, RECIPIENT_ALLIES));
        Assertions.assertEquals(MODE_RECIPIENT, ChatPrompt.chooseMode(true, false, NO_MODIFIERS, true, RECIPIENT_OTHER_PLAYER));
    }

    /**
     * Checks that a {@code /} line is a local command in single player, multiplayer and the viewer alike; it never becomes chat.
     */
    @Test
    void route_should_keep_slash_lines_local_in_every_mode_and_route_other_text() {
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/help", true, false));
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/debug", false, false));
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/nonsense", true, false), "An unknown command stays local too.");
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/cheat", true, false), "The cheat switch is local.");
        Assertions.assertEquals(ChatPrompt.Route.GAME_TEXT, ChatPrompt.route("show me the money", true, false), "A typed code is game text.");
        Assertions.assertEquals(ChatPrompt.Route.GAME_TEXT, ChatPrompt.route("gg", true, false));
        Assertions.assertEquals(ChatPrompt.Route.DROPPED, ChatPrompt.route("gg", false, false));
        Assertions.assertEquals(ChatPrompt.Route.DROPPED, ChatPrompt.route("  ", true, false));
    }

    /**
     * Checks that the community commands go to the channel chat while the game has one, and that the client's commands stay local.
     */
    @Test
    void route_should_send_community_commands_to_the_channel_chat_only_with_one() {
        Assertions.assertEquals(ChatPrompt.Route.COMMUNITY, ChatPrompt.route("/w Zeratul#abcd12 gg", true, true));
        Assertions.assertEquals(ChatPrompt.Route.COMMUNITY, ChatPrompt.route("/R thanks", true, true));
        Assertions.assertEquals(ChatPrompt.Route.COMMUNITY, ChatPrompt.route("/whois Zeratul", true, true));
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/w Zeratul gg", true, false), "Without the channel chat it is local.");
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/help", true, true), "The client's help stays local.");
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/join SC4J", true, true), "Channel commands stay out of the game.");
        Assertions.assertEquals(ChatPrompt.Route.COMMAND, ChatPrompt.route("/wx", true, true));
        Assertions.assertEquals(ChatPrompt.Route.GAME_TEXT, ChatPrompt.route("gg", true, true));
    }
}
