package org.fogofwarlabs.sc4j.scr.eud.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class ProfilerTest {
    @Test
    void actionsGoToTheStatementRunningAtTheTopOfTheTraceStack(@TempDir Path folder) throws Exception {
        Path epmap = folder.resolve("map.scx.epmap");

        Files.writeString(epmap, """
                                 H0: 00000000000000000000000000000000
                                 H1: 0102030405060708090a0b0c0d0e0f10
                                  - 00000011 : a.eps|main|3
                                  - 00000022 : a.eps|helper|9
                                 """, StandardCharsets.UTF_8);

        Profiler profiler = new Profiler(TraceReader.load(epmap));

        // The stack starts at address 0 here (no live space): depth N is the DWORD at 4N.
        profiler.written(4, 0x11);     // main, line 3, at depth 1
        profiler.action();
        profiler.written(8, 0x22);     // helper called from line 3, at depth 2
        profiler.action();
        profiler.action();
        profiler.written(8, 0);        // helper returns: line 3 runs again
        profiler.action();
        profiler.written(4, 0);        // main returns: nothing traced runs
        profiler.action();

        Map<String, Object> report = profiler.report();

        assertEquals(0.8, report.get("attributedShare"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> top = (List<Map<String, Object>>) report.get("top");

        assertEquals("a.eps|helper|9", top.get(0).get("where"));
        assertEquals(2L, top.get(0).get("actions"));
        assertEquals("a.eps|main|3", top.get(1).get("where"));
        assertEquals(2L, top.get(1).get("actions"));
    }

    @Test
    void withoutATraceOnlyTheFrameCountsAreReported() {
        Profiler profiler = new Profiler(null);

        profiler.record();
        profiler.action();
        profiler.action();

        Map<String, Object> report = profiler.report();

        assertEquals(2L, report.get("totalActions"));
        assertEquals(1L, report.get("totalTriggers"));
    }
}
