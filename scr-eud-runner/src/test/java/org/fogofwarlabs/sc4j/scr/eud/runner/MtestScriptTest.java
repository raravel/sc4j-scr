package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MtestScriptTest {
    private static MtestScript.Step only(String line) {
        MtestScript script = MtestScript.parse(List.of(line));

        assertEquals(List.of(), script.getProblems(), line);
        assertEquals(1, script.getSteps().size(), line);

        return script.getSteps().getFirst();
    }

    private static MtestScript.Problem problem(String line) {
        MtestScript script = MtestScript.parse(List.of(line));

        assertEquals(1, script.getProblems().size(), line);
        assertTrue(script.getSteps().isEmpty(), line);

        return script.getProblems().getFirst();
    }

    @Test
    void commentsBlankLinesAndByteOrderMarkAreSkipped() {
        MtestScript script = MtestScript.parse(List.of("﻿# title", "", "   ", "  # indented", "wait 1"));

        assertEquals(List.of(), script.getProblems());
        assertEquals(List.of(new MtestScript.Wait(5, 1)), script.getSteps());
    }

    @Test
    void waitCountsGameFrames() {
        assertEquals(new MtestScript.Wait(1, 300), only("wait 300"));
        assertEquals("out_of_range", problem("wait 0").code());
        assertEquals("bad_number", problem("wait x").code());
        assertEquals("missing_argument", problem("wait").code());
        assertEquals("extra_argument", problem("wait 1 # note").code());
    }

    @Test
    void keyHoldsForTheDefaultOrGivenFrames() {
        assertEquals(new MtestScript.KeyStep(1, MtestScript.KeyAction.HOLD, 0x20, "SPACE", MtestScript.DEFAULT_HOLD_FRAMES),
                     only("key SPACE"));
        assertEquals(new MtestScript.KeyStep(1, MtestScript.KeyAction.HOLD, '2', "2", 10), only("key 2 10"));
        assertEquals(new MtestScript.KeyStep(1, MtestScript.KeyAction.DOWN, 'A', "a", 0), only("keydown a"));
        assertEquals(new MtestScript.KeyStep(1, MtestScript.KeyAction.UP, 0x70, "F1", 0), only("keyup F1"));
        assertEquals("unknown_key", problem("key SPACEBAR").code());
        assertEquals("out_of_range", problem("key A 0").code());
    }

    @Test
    void keyNamesCoverLettersDigitsFunctionKeypadAndNamedKeys() throws Exception {
        assertEquals('Z', MtestScript.parseKey("z", ""));
        assertEquals('0', MtestScript.parseKey("0", ""));
        assertEquals(0x7B, MtestScript.parseKey("F12", ""));
        assertEquals(0x69, MtestScript.parseKey("KP_9", ""));
        assertEquals(0x0D, MtestScript.parseKey("ENTER", ""));
        assertEquals(0x1B, MtestScript.parseKey("escape", ""));
        assertEquals(0x28, MtestScript.parseKey("DOWN", ""));
    }

    @Test
    void clickTakesAMapPixelAnOptionalButtonAndHoldFrames() {
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.CLICK, 1024, 768, MtestScript.LEFT_BUTTON,
                                               MtestScript.DEFAULT_HOLD_FRAMES), only("click 1024,768"));
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.CLICK, 1, 2, MtestScript.RIGHT_BUTTON,
                                               MtestScript.DEFAULT_HOLD_FRAMES), only("click 1,2 right"));
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.CLICK, 1, 2, MtestScript.LEFT_BUTTON, 3), only("click 1,2 3"));
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.CLICK, 1, 2, MtestScript.MIDDLE_BUTTON, 4),
                     only("click 1,2 middle 4"));
        assertEquals("bad_number", problem("click 0.5,0.5").code());
        assertEquals("bad_point", problem("click 10").code());
        assertEquals("out_of_range", problem("click 9000,1").code());
        assertEquals("unknown_button", problem("click 1,2 back 4").code());
    }

    @Test
    void pressAndReleaseSplitAClick() {
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.PRESS, 5, 6, MtestScript.LEFT_BUTTON, 0), only("press 5,6"));
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.RELEASE, 0, 0, MtestScript.RIGHT_BUTTON, 0),
                     only("release right"));
        assertEquals(new MtestScript.MouseStep(1, MtestScript.MouseAction.RELEASE, 0, 0, MtestScript.LEFT_BUTTON, 0), only("release"));
    }

    @Test
    void expectDeathsReadsOnePlayerAndUnit() {
        MtestScript.Expect expect = (MtestScript.Expect) only("expect deaths P1 2 == 1");

        assertEquals(MtestScript.Subject.DEATHS, expect.subject());
        assertEquals(0, expect.player());
        assertEquals(2, expect.unit());
        assertTrue(expect.holds(1));
        assertFalse(expect.holds(0));
        assertEquals("bad_player", problem("expect deaths 1 2 == 1").code());
        assertEquals("out_of_range", problem("expect deaths P13 2 == 1").code());
        assertEquals("out_of_range", problem("expect deaths P1 228 == 1").code());
        assertEquals("bad_operator", problem("expect deaths P1 2 = 1").code());
    }

    @Test
    void expectMemoryReadsAnAlignedDword() {
        MtestScript.Expect expect = (MtestScript.Expect) only("expect memory 0x58A3C4 >= 0x5");

        assertEquals(0x58A3C4, expect.address());
        assertEquals(5, expect.value());
        assertTrue(expect.holds(0xFFFFFFFFL));
        assertEquals("unaligned_address", problem("expect memory 0x58A3C5 == 1").code());
        assertEquals("bad_address", problem("expect memory 5808068 == 1").code());
    }

    @Test
    void expectTextIsAQuotedSubstring() {
        MtestScript.Expect expect = (MtestScript.Expect) only("expect text contains \"봉인 \\\"조각\\\"\"");

        assertEquals("봉인 \"조각\"", expect.text());
        assertEquals("bad_text", problem("expect text contains 봉인").code());
        assertEquals("bad_text", problem("expect text contains \"open").code());
        assertEquals("extra_argument", problem("expect text contains \"a\" b").code());
        assertEquals("bad_operator", problem("expect text equals \"a\"").code());
    }

    @Test
    void expectUnitsComparesTheLiveUnitCount() {
        MtestScript.Expect expect = (MtestScript.Expect) only("expect units < 1700");

        assertEquals(MtestScript.Subject.UNITS, expect.subject());
        assertNull(expect.text());
        assertEquals("unknown_subject", problem("expect sprites < 3").code());
    }

    @Test
    void screenshotLinesWaitForTheScreenshotPhase() {
        assertEquals("screenshot_phase", problem("@window 1920x1080").code());
        assertEquals("screenshot_phase", problem("shot menu.png").code());
        assertEquals("unsupported_command", problem("type hello").code());
        assertEquals("unknown_command", problem("sleep 3").code());
    }

    @Test
    void everyProblemIsReportedWithItsLine() {
        MtestScript script = MtestScript.parse(List.of("wait 1", "key NOPE", "wait 2", "expect units ~ 3"));

        assertEquals(2, script.getProblems().size());
        assertEquals(2, script.getProblems().get(0).line());
        assertEquals(MtestScript.KEY_USAGE, script.getProblems().get(0).usage());
        assertEquals(4, script.getProblems().get(1).line());
    }

    @Test
    void plannedFramesSumWaitsAndHolds() {
        assertEquals(300 + 8 + 10 + 5, MtestScript.parse(List.of("wait 300", "key A", "click 1,1 10", "wait 5", "expect units > 0"))
                                                 .getPlannedFrames());
    }
}
