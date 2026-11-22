package org.fogofwarlabs.sc4j.scr.client;

import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChannelChat;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatCommand;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntry;
import org.fogofwarlabs.sc4j.engine.net.beaconlink.community.chat.ChatEntryKind;
import org.fogofwarlabs.sc4j.scr.game.presentation.MessageLines;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Brings the Beacon.Link community into a network game's message area from the shared channel chat's log: a received whisper shows as the
 * install's {@code bnet_chat_whisperFromPrefix} with the sender's tagged name, a space and the text, a delivered whisper as
 * {@code bnet_chat_whisperToPrefix} with the target's tagged name, both in the whisper style through
 * {@link MessageLines#writeWhisper(String, String, byte[])}; a whisper to a player who is not online is answered with
 * {@code bnet_chat_whisper_invalidUser}. The community commands typed in the chat box ({@link #isCommand}) run in the channel chat
 * ({@link ChannelChat#submit}); its answers and errors, such as the away notice after a whisper, go to the message area in the chat's order,
 * while channel lines and member notices stay in the channel screens. The chat hides blocked players' whispers.
 * <p>
 * The community calls the chat while the network polls; every method is used under the network's lock, and {@link #takeWrites} hands the
 * collected writes to the thread that owns the message lines.
 */
public final class GameCommunityChat {
    /**
     * The community commands the chat box runs in a game: whispers, replies, player information, ignoring, friends and away.
     */
    private static final @NotNull Set<ChatCommand> COMMANDS = EnumSet.of(ChatCommand.WHISPER, ChatCommand.REPLY, ChatCommand.WHOIS,
                                                                         ChatCommand.IGNORE, ChatCommand.UNIGNORE, ChatCommand.FRIENDS,
                                                                         ChatCommand.AWAY);
    private static final @NotNull String WHISPER_FROM_KEY = "bnet_chat_whisperFromPrefix";
    private static final @NotNull String WHISPER_TO_KEY = "bnet_chat_whisperToPrefix";
    private static final @NotNull String INVALID_USER_KEY = "bnet_chat_whisper_invalidUser";

    /**
     * Tests whether a typed line is a community command the chat box runs in a game.
     *
     * @param line the typed line
     * @return {@code true} if the line runs one of the in-game community commands ({@link ChatCommand#find}), {@code false} otherwise
     */
    public static boolean isCommand(@NotNull String line) {
        return COMMANDS.contains(ChatCommand.find(line));
    }

    private final @NotNull ChannelChat chat;

    private final @NotNull String whisperFrom;
    private final @NotNull String whisperTo;
    private final byte @NotNull [] invalidUser;

    private final @NotNull Consumer<@NotNull ChatEntry> log;
    private final @NotNull List<@NotNull Consumer<@NotNull MessageLines>> writes;

    /**
     * Creates a {@link GameCommunityChat} instance.
     *
     * @param chat        the channel chat, which runs the typed commands
     * @param installText the install's texts by key, an empty text for an unknown key
     */
    public GameCommunityChat(@NotNull ChannelChat chat, @NotNull Function<@NotNull String, @NotNull String> installText) {
        this.chat = chat;

        this.whisperFrom = installText.apply(WHISPER_FROM_KEY);
        this.whisperTo   = installText.apply(WHISPER_TO_KEY);
        this.invalidUser = installText.apply(INVALID_USER_KEY).getBytes(StandardCharsets.UTF_8);

        this.log    = this::addEntry;
        this.writes = new ArrayList<>();
    }

    /**
     * Starts taking the channel chat's new entries; the chat's earlier entries are not shown.
     */
    public void attach() {
        this.chat.addLog(this.log, false);
    }

    /**
     * Runs a typed community command in the channel chat.
     *
     * @param line the typed line
     */
    public void submit(@NotNull String line) {
        this.chat.submit(line);
    }

    /**
     * Takes the message area writes collected since the last call.
     *
     * @return the writes, in order
     */
    public @NotNull List<@NotNull Consumer<@NotNull MessageLines>> takeWrites() {
        List<Consumer<MessageLines>> taken = List.copyOf(this.writes);

        this.writes.clear();

        return taken;
    }

    /**
     * Shows a channel chat entry: a whisper with the install's whisper prefix, the error of a whisper to a player who is not online with the
     * install's invalid user text, and an entry that names no player (the answer or error of a typed command) as it is. Lines and member
     * notices are left to the channel screens.
     *
     * @param entry the entry
     */
    private void addEntry(@NotNull ChatEntry entry) {
        String name = entry.getPlayerName();

        if (entry.getPlayer() == null || name == null) {
            byte[] text = (entry.getPrefix() + entry.getText()).getBytes(StandardCharsets.UTF_8);

            if (entry.getKind() == ChatEntryKind.ERROR) {
                this.writes.add(lines -> lines.writeErrorMessage(text));
            } else {
                this.writes.add(lines -> lines.writeGameMessage(text, 0));
            }

            return;
        }

        switch (entry.getKind()) {
            case WHISPER -> {
                String format = entry.isSent() ? this.whisperTo : this.whisperFrom;
                byte[] text = entry.getText().getBytes(StandardCharsets.UTF_8);

                this.writes.add(lines -> lines.writeWhisper(format, name, text));
            }
            case ERROR -> this.writes.add(lines -> lines.writeErrorMessage(this.invalidUser));
            default -> {
            }
        }
    }

    /**
     * Stops taking the channel chat's entries.
     */
    public void detach() {
        this.chat.removeLog(this.log);
    }
}
