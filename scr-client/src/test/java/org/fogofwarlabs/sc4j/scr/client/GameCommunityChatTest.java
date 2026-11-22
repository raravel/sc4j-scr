package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.ChatLineKind;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerPresence;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.PlayerStatus;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.RecordingCommunitySession;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.CommunityText;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Checks the Beacon.Link community in a game's message area: whispers in the install's format, the in-game whisper commands, the answers of
 * the channel chat in its order and the hidden lines.
 */
final class GameCommunityChatTest {
    /**
     * The install's English texts of the in-game whispers.
     */
    private static final @NotNull Map<String, String> TEXTS = Map.of("bnet_chat_whisperFromPrefix", "<From: %s>",
                                                                     "bnet_chat_whisperToPrefix", "<To: %s>",
                                                                     "bnet_chat_whisper_invalidUser", "Invalid user.");
    private static final @NotNull String CHANNEL_BASE = "StarCraft Remastered";
    private static final @NotNull String MOD_ID = "scr";
    private static final @NotNull String MOD_VERSION = "0.1.0";
    private static final int SELF_KEY = 1;
    private static final int ZERATUL_KEY = 2;
    private static final int TASSADAR_KEY = 3;
    private static final @NotNull String AWAY_TEXT = "meditating";
    /**
     * The layout that keeps every text on one line.
     */
    private static final MessageLines.@NotNull TextLayout SINGLE_LINE = text -> new int[]{text.length};
    /**
     * The first message line of a game, then the lines the next messages take.
     */
    private static final int @NotNull [] WRITTEN_LINES = {10, 0, 1, 2, 3};
    private static final int WHISPER_STYLE = 3;
    private static final int GAME_MESSAGE_STYLE = 3;

    private static @NotNull PlayerPresence createPlayer(int key, @NotNull String name) {
        return RecordingCommunitySession.createPresence(key, name, MOD_ID, MOD_VERSION, true, PlayerStatus.IN_GAME);
    }

    private static @NotNull MessageLines createLines() {
        MessageLines lines = new MessageLines();

        lines.setLayout(SINGLE_LINE);
        lines.setHostClock(() -> 0);

        return lines;
    }

    private static @NotNull List<String> write(@NotNull GameCommunityChat game, @NotNull MessageLines lines) {
        List<Consumer<MessageLines>> writes = game.takeWrites();
        List<String> written = new ArrayList<>();

        for (Consumer<MessageLines> write : writes) {
            write.accept(lines);
        }

        for (int index = 0; index < writes.size(); index++) {
            written.add(new String(lines.getLine(WRITTEN_LINES[index]), StandardCharsets.UTF_8));
        }

        return written;
    }

    private final @NotNull PlayerPresence zeratul;
    private final @NotNull RecordingCommunitySession community;
    private final @NotNull ChannelChat chat;

    GameCommunityChatTest() {
        this.zeratul   = createPlayer(ZERATUL_KEY, "Zeratul");
        this.community = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));
        this.community.setChannelSetup(channel -> channel.addMember(this.zeratul));
        this.chat = new ChannelChat(this.community, Locale.ENGLISH, CHANNEL_BASE);
        this.chat.open();
    }

    /**
     * Checks that a whisper during a game shows as {@code <From: Name#tag> text} in the whisper style with the transmission sound, while the
     * chat's earlier entries and the channel's lines stay out of the message area.
     */
    @Test
    void received_whisper_should_show_with_the_from_prefix_in_the_whisper_style() {
        GameCommunityChat game = new GameCommunityChat(this.chat, TEXTS::get);
        MessageLines lines = createLines();

        this.community.receiveWhisper(this.zeratul, "before the game");
        game.attach();
        this.findChannel().receiveLine(this.zeratul, ChatLineKind.SAY, "channel line");
        this.community.receiveWhisper(this.zeratul, "gg");

        Assertions.assertEquals(List.of("<From: " + this.zeratul.getTaggedName() + "> gg"), write(game, lines));
        Assertions.assertEquals(WHISPER_STYLE, lines.getStyle(WRITTEN_LINES[0]));
        Assertions.assertEquals(1, lines.takeTransmissionSounds());
    }

    /**
     * Checks that {@code /w} from the chat box sends the whisper and echoes it as {@code <To: Name#tag> text} in the whisper style.
     */
    @Test
    void typed_whisper_should_send_and_show_with_the_to_prefix() {
        GameCommunityChat game = new GameCommunityChat(this.chat, TEXTS::get);
        MessageLines lines = createLines();

        game.attach();
        game.submit("/w Zeratul en taro adun");

        Assertions.assertEquals(1, this.community.getWhispers().size());
        Assertions.assertEquals(this.zeratul.getPlayer(), this.community.getWhispers().getFirst().getOther());
        Assertions.assertEquals("en taro adun", this.community.getWhispers().getFirst().getText());
        Assertions.assertEquals(List.of("<To: " + this.zeratul.getTaggedName() + "> en taro adun"), write(game, lines));
        Assertions.assertEquals(WHISPER_STYLE, lines.getStyle(WRITTEN_LINES[0]));
    }

    /**
     * Checks that {@code /r} to a player who went offline is answered with the install's invalid user text, that a whisper to an unknown name
     * shows the channel chat's error, and that the departure notice stays out of the message area.
     */
    @Test
    void whisper_to_offline_or_unknown_player_should_show_an_error() {
        GameCommunityChat game = new GameCommunityChat(this.chat, TEXTS::get);
        MessageLines lines = createLines();

        game.attach();
        this.community.receiveWhisper(this.zeratul, "brb");
        this.findChannel().removeMember(this.zeratul);
        game.submit("/r back?");
        game.submit("/w Tassadar hello");

        List<String> written = write(game, lines);

        Assertions.assertEquals(List.of("<From: " + this.zeratul.getTaggedName() + "> brb",
                                        "Invalid user.",
                                        CommunityText.UNKNOWN_PLAYER.format(Locale.ENGLISH, "Tassadar")), written);
        Assertions.assertEquals(GAME_MESSAGE_STYLE, lines.getStyle(WRITTEN_LINES[1]));
    }

    /**
     * Checks that a whisper to an away player shows the channel chat's away notice after the whisper.
     */
    @Test
    void whisper_to_away_player_should_show_the_away_notice_after_the_whisper() {
        PlayerPresence tassadar = RecordingCommunitySession.createAwayPresence(TASSADAR_KEY, "Tassadar", MOD_ID, MOD_VERSION, AWAY_TEXT);
        RecordingCommunitySession awayCommunity = new RecordingCommunitySession(createPlayer(SELF_KEY, "Raynor"));

        awayCommunity.setChannelSetup(channel -> channel.addMember(tassadar));

        ChannelChat awayChat = new ChannelChat(awayCommunity, Locale.ENGLISH, CHANNEL_BASE);

        awayChat.open();

        GameCommunityChat game = new GameCommunityChat(awayChat, TEXTS::get);
        MessageLines lines = createLines();

        game.attach();
        game.submit("/w Tassadar hello");

        Assertions.assertEquals(List.of("<To: " + tassadar.getTaggedName() + "> hello",
                                        CommunityText.IS_AWAY_TEXT.format(Locale.ENGLISH, tassadar.getTaggedName(), AWAY_TEXT)), write(game, lines));
    }

    /**
     * Checks that a blocked player's whisper stays hidden and that a detached game takes no whisper.
     */
    @Test
    void blocked_or_detached_whispers_should_stay_hidden() {
        GameCommunityChat game = new GameCommunityChat(this.chat, TEXTS::get);
        MessageLines lines = createLines();

        game.attach();
        this.community.ignore(this.zeratul.getPlayer());
        this.community.receiveWhisper(this.zeratul, "hidden");
        this.community.unignore(this.zeratul.getPlayer());

        Assertions.assertEquals(List.of(), write(game, lines));

        game.detach();
        this.community.receiveWhisper(this.zeratul, "after the game");

        Assertions.assertEquals(List.of(), write(game, lines));
        Assertions.assertEquals(1, this.community.getListenerCount(), "Only the channel chat listens after the game.");
    }

    /**
     * Checks the community commands the chat box runs in a game.
     */
    @Test
    void is_command_should_take_the_in_game_community_commands_only() {
        Assertions.assertTrue(GameCommunityChat.isCommand("/w Zeratul gg"));
        Assertions.assertTrue(GameCommunityChat.isCommand("  /Reply thanks"));
        Assertions.assertTrue(GameCommunityChat.isCommand("/away"));
        Assertions.assertFalse(GameCommunityChat.isCommand("/join SC4J"));
        Assertions.assertFalse(GameCommunityChat.isCommand("/help"));
        Assertions.assertFalse(GameCommunityChat.isCommand("gg"));
    }

    private RecordingCommunitySession.@NotNull Channel findChannel() {
        RecordingCommunitySession.Channel channel = this.community.findChannel(ChannelChat.findDefaultChannel(CHANNEL_BASE, List.of()));

        Assertions.assertNotNull(channel);

        return channel;
    }
}
