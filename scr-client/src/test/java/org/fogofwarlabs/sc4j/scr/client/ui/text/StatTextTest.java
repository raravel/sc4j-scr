package org.fogofwarlabs.sc4j.scr.client.ui.text;

import org.fogofwarlabs.sc4j.scr.client.ui.input.HotkeyProfile;
import org.fogofwarlabs.sc4j.scr.client.world.ScrGameView;
import org.fogofwarlabs.sc4j.scr.core.LcgRandom;
import org.fogofwarlabs.sc4j.scr.core.test.ExternalInput;
import org.fogofwarlabs.sc4j.scr.core.test.RequiresInstallation;
import org.fogofwarlabs.sc4j.scr.game.state.Game;
import org.fogofwarlabs.sc4j.scr.game.state.GameData;
import org.fogofwarlabs.sc4j.scr.game.terrain.GameMap;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Checks the {@code stat_txt} string tables: the product name substitution of a parsed table, the committed game-specific button strings and
 * hotkeys with their isolation from the next game, and the strings a plugin overrides, unit names included.
 */
final class StatTextTest {
    private static final int MOVE = 664;
    private static final int CUSTOM = 217;

    /**
     * Encodes the committed text offsets and CP949 button text.
     *
     * @return the serialized text table
     */
    private static byte @NotNull [] createCommittedTextTable() {
        Charset korean = Charset.forName("x-windows-949");
        int headerLength = (MOVE + 1) * Short.BYTES;
        byte[] table = new byte[headerLength];

        table[0] = (byte) MOVE;
        table[1] = (byte) (MOVE >> Byte.SIZE);

        int emptyTextOffset = headerLength;
        int customTextOffset = headerLength + 1;
        byte[] button = ("q").getBytes(StandardCharsets.US_ASCII);
        byte[] text = "[Q] 스킬".getBytes(korean);
        ByteArrayOutputStream body = new ByteArrayOutputStream();

        body.write(0);
        body.writeBytes(button);
        body.write(0);
        body.writeBytes(text);
        body.write(0);

        int moveTextOffset = headerLength + body.size();

        body.writeBytes(new byte[]{'z', 1, 4, 'M', 'o', 'v', 'e', ' ', 2, '(', 'Z', ')', 0});

        for (int identifier = 1; identifier <= MOVE; identifier++) {
            int offset = identifier == CUSTOM ? customTextOffset : identifier == MOVE ? moveTextOffset : emptyTextOffset;

            table[identifier * Short.BYTES]     = (byte) offset;
            table[identifier * Short.BYTES + 1] = (byte) (offset >> Byte.SIZE);
        }

        ByteArrayOutputStream all = new ByteArrayOutputStream();

        all.writeBytes(table);
        all.writeBytes(body.toByteArray());

        return all.toByteArray();
    }

    @Test
    void parse_should_substitute_product_name_in_string_tables() throws IOException {
        byte[] json = "[{\"Key\": \"1\", \"Value\": \"Battle.net\\u00AE\"}, {\"Key\": \"2\", \"Value\": \"Log in to Battle.net\\nnow\"}]"
                .getBytes(StandardCharsets.UTF_8);
        StatText text = StatText.parse(json);

        Assertions.assertEquals("Beacon.Link", text.getString(1));
        Assertions.assertEquals("Log in to Beacon.Link\nnow", text.getString(2));
    }

    @RequiresInstallation
    @Test
    void committed_stat_text_table_supplies_button_names_and_hotkeys_for_its_game_only() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> switch (identifier) {
            case MOVE -> "m\\u0000\\u0003M\\u0001ove";
            case CUSTOM -> "Young Chrysalis\\u0000*\\u0000Powerups";
            default -> "";
        });
        HotkeyProfile profile = new HotkeyProfile(text, HotkeyProfile.DEVICE, Map.of());

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));

            game.getGameContext().setStatTextTable(createCommittedTextTable());

            Assertions.assertEquals("q\0[Q] 스킬", text.getString(new ScrGameView(game), CUSTOM), "Type 0: the text from the pointer plus two.");
            Assertions.assertEquals("q\0[Q] 스킬", profile.getString(new ScrGameView(game), CUSTOM), "An id outside the profile table is the plain string.");
            Assertions.assertEquals('q', profile.getHotkey(new ScrGameView(game), CUSTOM), "The map's key.");
            Assertions.assertEquals("z\u0001\u0004Move \u0002(Z)", profile.getString(new ScrGameView(game), MOVE), "A profile id is the plain string.");
            Assertions.assertEquals('z', profile.getHotkey(new ScrGameView(game), MOVE), "A profile id takes the map's key.");

            Game next = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));

            Assertions.assertEquals("Young Chrysalis\0*\0Powerups", text.getString(new ScrGameView(next), CUSTOM), "A new game reads the installed strings.");
            Assertions.assertEquals('Y', profile.getHotkey(new ScrGameView(next), CUSTOM));
            Assertions.assertEquals("m\0Move [\u0003M\u0001]", profile.getString(new ScrGameView(next), MOVE), "A new game applies the profile.");
            Assertions.assertEquals('m', profile.getHotkey(new ScrGameView(next), MOVE));
        }
    }

    @Test
    void plugin_overrides_win_over_the_committed_table() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> identifier == CUSTOM ? "Installed" : "");
        Game game = Game.create(null, new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
        ScrGameView view = new ScrGameView(game);

        game.getGameContext().setStatTextTable(createCommittedTextTable());
        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, "Script");
        game.getInterfaceLink().getStatTextOverrides().set(MOVE + 1, "Beyond the table");

        Assertions.assertEquals("Script", text.getString(view, CUSTOM));
        Assertions.assertEquals("Beyond the table", text.getString(view, MOVE + 1));

        game.getInterfaceLink().getStatTextOverrides().set(CUSTOM, null);

        Assertions.assertEquals("q\0[Q] 스킬", text.getString(view, CUSTOM), "The committed table again.");
    }

    @RequiresInstallation
    @Test
    void an_overridden_unit_name_wins_over_the_installed_name() throws IOException {
        StatText text = StatTextScenario.createStatText(MOVE, identifier -> identifier == 1 ? "Terran Marine" : "");

        try (GameData data = GameData.loadFrom(ExternalInput.INSTALLATION.getPath())) {
            Game game = Game.create(data.createGameCopy(), new GameMap(1, 1, 0, new int[1]), new LcgRandom(0));
            ScrGameView view = new ScrGameView(game);

            Assertions.assertEquals("Terran Marine", text.getUnitName(view, 0));

            game.getInterfaceLink().getStatTextOverrides().set(1, "Space Marine");

            Assertions.assertNull(game.getUnitDisplayName(0));
            Assertions.assertEquals("Space Marine", text.getUnitName(view, 0));
        }
    }
}
