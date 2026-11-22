package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MtestConverterTest {
    private static String convert(String... lines) {
        List<String> source = List.of(lines);
        MtestScript script = MtestScript.parse(source);

        assertEquals(List.of(), script.getProblems());

        return MtestConverter.convert("a.mtest", source, script);
    }

    private static String body(String converted) {
        String[] lines = converted.split("\n");
        StringBuilder body = new StringBuilder();

        // The two header comments and the function line, then the body up to the closing brace.
        for (int index = 3; index < lines.length - 1; index++) {
            body.append(lines[index].strip()).append('\n');
        }

        return body.toString();
    }

    @Test
    void everyStepBecomesItsTestCall() {
        String converted = convert("# title", "", "wait 10", "key a 3", "keydown ENTER", "keyup ENTER", "click 10,20 right 4",
                                   "press 1,2", "release middle", "view 64,96", "chat it's \\ done", "shot end",
                                   "expect deaths P2 37 >= 1", "expect memory 0x58A364 != 0", "expect units < 5",
                                   "expect text contains \"a 'b'\"", "expect text not contains \"c\"", "wait 7");

        assertEquals("""
                     // title

                     yield t.wait(9);
                     yield t.key('a', 3);
                     t.keyDown('ENTER');
                     t.keyUp('ENTER');
                     yield t.click(10, 20, 'right', 4);
                     t.press(1, 2, 'left');
                     t.release('middle');
                     t.view(64, 96);
                     t.chat('it\\'s \\\\ done');
                     t.shot('end');
                     t.expect(t.deaths(2, 37)).atLeast(1);
                     t.expect(t.memory(0x58A364)).not.toBe(0);
                     t.expect(t.units()).below(5);
                     t.expect(t.text()).toContain('a \\'b\\'');
                     t.expect(t.text()).not.toContain('c');
                     yield t.wait(7);
                     """, body(converted));
    }

    @Test
    void onlyTheFirstWaitIsShortened() {
        assertEquals("// wait 1: the game's first frame\nyield t.wait(1);\n", body(convert("wait 1", "wait 1")));
        // The hold starts one frame late; the shortened wait puts every later step back on the script's frame.
        assertEquals("yield t.key('A', 8);\nyield t.wait(4);\nyield t.wait(5);\n", body(convert("key A", "wait 5", "wait 5")));
    }
}
