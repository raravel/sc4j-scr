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
    void viewCentresOnAMapPixelWithoutFrames() {
        assertEquals(new MtestScript.View(1, 1024, 1024), only("view 1024,1024"));
        assertEquals(new MtestScript.View(1, 0, 0), only("VIEW 0,0"));
        assertEquals(new MtestScript.View(1, MtestScript.MAXIMUM_COORDINATE, 7), only("view " + MtestScript.MAXIMUM_COORDINATE + ",7"));
        assertEquals(0, MtestScript.parse(List.of("view 1,2", "view 3,4")).getPlannedFrames());
    }

    @Test
    void chatTakesTheRestOfTheLineWithoutFrames() {
        assertEquals(new MtestScript.Chat(1, "00ABC def"), only("chat 00ABC def"));
        assertEquals(new MtestScript.Chat(1, "Hello  World"), only("CHAT   Hello  World  "));
        assertEquals(new MtestScript.Chat(1, "x".repeat(MtestScript.MAXIMUM_CHAT_BYTES)),
                     only("chat " + "x".repeat(MtestScript.MAXIMUM_CHAT_BYTES)));
        assertEquals(0, MtestScript.parse(List.of("chat a", "chat b")).getPlannedFrames());
    }

    @Test
    void chatRejectsNoTextAndMoreThanTheChatBoxTakes() {
        assertEquals(new MtestScript.Problem(1, "missing_argument", "chat", MtestScript.CHAT_USAGE), problem("chat"));
        assertEquals("out_of_range", problem("chat " + "x".repeat(MtestScript.MAXIMUM_CHAT_BYTES + 1)).code());
        // Bytes, not characters: a Hangul syllable is three.
        assertEquals("out_of_range", problem("chat " + "\uAC00".repeat(27)).code());
        assertEquals(new MtestScript.Chat(1, "\uAC00".repeat(26)), only("chat " + "\uAC00".repeat(26)));
        assertEquals("bad_text", problem("chat a\tb").code());
        assertEquals("bad_text", problem("chat a\u0007b").code());
        // Only a line that starts with # is a comment.
        assertEquals(new MtestScript.Chat(1, "#hi"), only("chat #hi"));
    }

    @Test
    void viewRejectsAnythingButOnePoint() {
        assertEquals(new MtestScript.Problem(1, "missing_argument", "view", MtestScript.VIEW_USAGE), problem("view"));
        assertEquals("extra_argument", problem("view 1,2 3").code());
        assertEquals("extra_argument", problem("view 1,2 left").code());
        assertEquals("bad_point", problem("view 10").code());
        assertEquals("bad_point", problem("view 1,2,3").code());
        assertEquals("out_of_range", problem("view -1,5").code());
        assertEquals("bad_number", problem("view 0.5,1").code());
        assertEquals("out_of_range", problem("view 8192,1").code());
        assertEquals(MtestScript.VIEW_USAGE, problem("view x,y").usage());
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
    void expectTextNotContainsHoldsOnlyWhenNoLineHasTheText() {
        MtestScript.Expect present = (MtestScript.Expect) only("expect text contains \"전투 시작!\"");
        MtestScript.Expect absent = (MtestScript.Expect) only("expect text not contains \"전투 시작!\"");
        java.util.List<String> shown = java.util.List.of("", "\u0006전투 시작!");
        java.util.List<String> cleared = java.util.List.of("", " ");

        assertEquals("not contains", absent.operator());
        assertEquals("전투 시작!", absent.text());
        assertTrue(present.holdsText(shown));
        assertFalse(present.holdsText(cleared));
        assertFalse(absent.holdsText(shown));
        assertTrue(absent.holdsText(cleared));
        assertEquals("bad_operator", problem("expect text not equals \"a\"").code());
        assertEquals("bad_text", problem("expect text not contains").code());
        assertEquals("missing_argument", problem("expect text not").code());
        assertEquals("missing_argument", problem("expect text").code());
    }

    @Test
    void expectUnitsComparesTheLiveUnitCount() {
        MtestScript.Expect expect = (MtestScript.Expect) only("expect units < 1700");

        assertEquals(MtestScript.Subject.UNITS, expect.subject());
        assertNull(expect.text());
        assertEquals("unknown_subject", problem("expect sprites < 3").code());
    }

    @Test
    void windowLinesWaitForTheScreenshotPhase() {
        assertEquals("screenshot_phase", problem("@window 1920x1080").code());
        assertEquals("unsupported_command", problem("type hello").code());
        assertEquals("unknown_command", problem("sleep 3").code());
    }

    @Test
    void shotTakesAnOptionalName() {
        assertEquals(new MtestScript.Shot(1, "selected"), only("shot selected"));
        assertEquals(new MtestScript.Shot(1, "A-b_9"), only("SHOT A-b_9"));
        assertEquals(new MtestScript.Shot(1, "x".repeat(40)), only("shot " + "x".repeat(40)));
    }

    @Test
    void shotWithoutANameIsNamedAfterItsLine() {
        MtestScript script = MtestScript.parse(List.of("wait 1", "# comment", "shot"));

        assertEquals(List.of(), script.getProblems());
        assertEquals(List.of(new MtestScript.Shot(3, "line3")), script.getShots());
        assertEquals(1, script.getPlannedFrames());
    }

    @Test
    void shotNamesAreUniqueIgnoringCase() {
        MtestScript script = MtestScript.parse(List.of("shot start", "shot Start", "shot line4", "shot"));

        assertEquals(List.of(new MtestScript.Shot(1, "start"), new MtestScript.Shot(3, "line4")), script.getShots());
        assertEquals(2, script.getProblems().size());
        assertEquals(new MtestScript.Problem(2, "duplicate_shot", "Start", MtestScript.SHOT_USAGE), script.getProblems().get(0));
        assertEquals(new MtestScript.Problem(4, "duplicate_shot", "line4", MtestScript.SHOT_USAGE), script.getProblems().get(1));
    }

    @Test
    void shotNameMustBeShortPlainText() {
        assertEquals("bad_name", problem("shot menu.png").code());
        assertEquals("bad_name", problem("shot ../x").code());
        assertEquals("bad_name", problem("shot 전투").code());
        assertEquals("bad_name", problem("shot " + "x".repeat(41)).code());
        assertEquals(MtestScript.SHOT_USAGE, problem("shot a/b").usage());
        assertEquals("extra_argument", problem("shot a b").code());
    }

    @Test
    void aScriptHoldsAtMostSixteenShots() {
        List<String> lines = new java.util.ArrayList<>();

        for (int index = 0; index < MtestScript.MAXIMUM_SHOTS + 2; index++) {
            lines.add("shot s" + index);
        }

        MtestScript script = MtestScript.parse(lines);

        assertEquals(MtestScript.MAXIMUM_SHOTS, script.getShots().size());
        assertEquals(2, script.getProblems().size());
        assertEquals("too_many_shots", script.getProblems().get(0).code());
        assertEquals(MtestScript.MAXIMUM_SHOTS + 1, script.getProblems().get(0).line());
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
