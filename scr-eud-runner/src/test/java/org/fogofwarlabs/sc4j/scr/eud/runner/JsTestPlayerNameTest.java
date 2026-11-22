package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class JsTestPlayerNameTest {
    @TempDir
    Path folder;

    private JsTest load(String text) throws IOException {
        Path file = this.folder.resolve("a.test.js");

        Files.writeString(file, text, StandardCharsets.UTF_8);

        return JsTest.load(file);
    }

    @Test
    void withoutAPlayerLineTheDefaultNamePlays() throws IOException {
        JsTest test = this.load("function* test(t) { yield t.wait(1); }\n");

        assertEquals(RunnerMain.DEFAULT_PLAYER_NAME, test.playerName());
        assertEquals(List.of(), test.problems());
    }

    @Test
    void thePlayerLineNamesThePlayer() throws IOException {
        JsTest test = this.load("// round 40 boss\n  //  @player  Raravel  \nfunction* test(t) { yield t.wait(1); }\n");

        assertEquals("Raravel", test.playerName());
        assertEquals(List.of(), test.problems());
    }

    @Test
    void aNameOverTwentyFourBytesIsAProblem() throws IOException {
        JsTest test = this.load("\n// @player 가나다라마바사아자\nfunction* test(t) {}\n");

        assertEquals(RunnerMain.DEFAULT_PLAYER_NAME, test.playerName());

        List<Map<String, Object>> problems = test.problems();

        assertEquals(1, problems.size());
        assertEquals("bad_player", problems.getFirst().get("code"));
        assertEquals(2, problems.getFirst().get("line"));
    }
}
