/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestSignoffDelayModels {

    @Test
    public void testInterconnectTableParsesBothCorners(@TempDir Path dir) throws IOException {
        Path table = dir.resolve("node_delay.txt");
        Files.write(table, Arrays.asList(
                "# comment",
                "node_delay",
                "NODE_HSINGLE 12.5 10.25",
                "NODE_VLONG7 44.0",
                "BRANCHES_PASSED 7.2 2.7",
                "NET_FANOUT 0.3 0.2",
                "NOT_A_REAL_INTENT 1 1"), StandardCharsets.UTF_8);
        VersalInterconnectDelayModel m = new VersalInterconnectDelayModel(table);
        Assertions.assertEquals(TimingFidelity.SIGNOFF, m.getFidelity());
        Assertions.assertEquals(12.5f, m.getNodeDelayPs(IntentCode.NODE_HSINGLE, Corner.SLOW_MAX));
        Assertions.assertEquals(10.25f, m.getNodeDelayPs(IntentCode.NODE_HSINGLE, Corner.SLOW_MIN));
        // A single value applies to both corners.
        Assertions.assertEquals(44.0f, m.getNodeDelayPs(IntentCode.NODE_VLONG7, Corner.SLOW_MIN));
        Assertions.assertNull(m.getNodeDelayPs(IntentCode.NODE_HQUAD, Corner.SLOW_MAX));
    }

    @Test
    public void testInterconnectTableRejectsEmpty(@TempDir Path dir) throws IOException {
        Path table = dir.resolve("empty.txt");
        Files.write(table, Arrays.asList("# nothing here"), StandardCharsets.UTF_8);
        Assertions.assertThrows(IOException.class, () -> new VersalInterconnectDelayModel(table));
    }

    @Test
    public void testLogicTableReadsIntraSiteSection(@TempDir Path dir) throws IOException {
        Path arcs = dir.resolve("logic_delay.txt");
        Files.write(arcs, Arrays.asList("logic_delay", "SLICEL.AFF CLK Q prop 91 71 1 FDRE"),
                StandardCharsets.UTF_8);
        Path intra = dir.resolve("intrasite_delay.txt");
        Files.write(intra, Arrays.asList("intrasite_delay", "SLICEL A6LUT/O6 AFF2/D 67 16 40"),
                StandardCharsets.UTF_8);
        VersalLogicDelayModel m = new VersalLogicDelayModel(arcs, intra);
        Assertions.assertEquals(1, m.getArcCount());
        Assertions.assertEquals(1, m.getIntraSiteArcCount());
        Assertions.assertEquals(67f, m.getIntraSiteDelayPs(SiteTypeEnum.SLICEL, "A6LUT/O6", "AFF2/D", Corner.SLOW_MAX));
        Assertions.assertEquals(16f, m.getIntraSiteDelayPs(SiteTypeEnum.SLICEL, "A6LUT/O6", "AFF2/D", Corner.SLOW_MIN));
        Assertions.assertNull(m.getIntraSiteDelayPs(SiteTypeEnum.SLICEM, "A6LUT/O6", "AFF2/D", Corner.SLOW_MAX));
    }

    @Test
    public void testLogicTableLookupIsByBELAndKind(@TempDir Path dir) throws IOException {
        Design design = RapidWrightDCP.loadDCP("versal_cout_hq2.dcp");
        Cell ff = null;
        for (Cell c : design.getCells()) {
            if (c.getSite() != null && c.getType().startsWith("FD")) {
                ff = c;
                break;
            }
        }
        Assertions.assertNotNull(ff, "design has a placed flip-flop");
        String bel = VersalLogicDelayModel.qualifiedBEL(ff);
        Assertions.assertTrue(bel.startsWith("SLICE"), bel);

        Path table = dir.resolve("logic_delay.txt");
        Files.write(table, Arrays.asList(
                "logic_delay",
                bel + " CLK Q prop 91 71 1556 FDRE",
                bel + " CLK D setup 45 30 100 FDRE",
                bel + " CLK D hold 12 8 100 FDRE",
                "SLICEL.A6LUT A1 O6 prop 120 90 5 LUT6"), StandardCharsets.UTF_8);
        VersalLogicDelayModel m = new VersalLogicDelayModel(table);
        Assertions.assertEquals(4, m.getArcCount());
        Assertions.assertEquals(91f, m.getPropagationDelayPs(ff, "CLK", "Q", Corner.SLOW_MAX));
        Assertions.assertEquals(71f, m.getPropagationDelayPs(ff, "CLK", "Q", Corner.SLOW_MIN));
        Assertions.assertEquals(45f, m.getSetupPs(ff, "CLK", "D", Corner.SLOW_MAX));
        Assertions.assertEquals(8f, m.getHoldPs(ff, "CLK", "D", Corner.SLOW_MIN));
        // A requirement is not a propagation arc, and vice versa.
        Assertions.assertNull(m.getPropagationDelayPs(ff, "CLK", "D", Corner.SLOW_MAX));
        Assertions.assertNull(m.getSetupPs(ff, "CLK", "Q", Corner.SLOW_MAX));
    }

    @Test
    public void testTimingEdgeCornersDefaultToMax() {
        TimingGraph graph = new TimingGraph(new Design("t", "xcvu3p-ffvc1517-2-e"));
        TimingVertex u = new TimingVertex("u");
        TimingVertex v = new TimingVertex("v");
        graph.addVertex(u);
        graph.addVertex(v);
        TimingEdge e = new TimingEdge(graph, u, v);
        graph.addEdge(u, v, e);

        // The single-argument setters describe a one-corner model: both
        // corners read back the same number.
        e.setLogicDelay(100f);
        e.setNetDelay(50f);
        Assertions.assertEquals(150f, e.getDelay());
        Assertions.assertEquals(150f, e.getDelay(Corner.SLOW_MAX));
        Assertions.assertEquals(150f, e.getDelay(Corner.SLOW_MIN));

        e.setLogicDelay(100f, 80f);
        e.setNetDelay(50f, 30f);
        Assertions.assertEquals(150f, e.getDelay());
        Assertions.assertEquals(110f, e.getDelay(Corner.SLOW_MIN));
        Assertions.assertEquals(80f, e.getLogicDelay(Corner.SLOW_MIN));
        Assertions.assertEquals(30f, e.getNetDelay(Corner.SLOW_MIN));
        // The graph weight stays the maximum corner, which is what the router ranks on.
        Assertions.assertEquals(150.0, graph.getEdgeWeight(e));
    }
}
