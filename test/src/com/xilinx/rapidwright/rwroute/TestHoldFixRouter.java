/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestHoldFixRouter {

    /** Routed sinks of small signal nets whose source sits in another tile: candidates for a detour. */
    static List<SitePinInst> pickSinks(Design design, int count) {
        List<SitePinInst> out = new ArrayList<>();
        for (Net net : design.getNets()) {
            if (net.isStaticNet() || net.isClockNet() || net.getSource() == null || !net.hasPIPs()) continue;
            if (net.getSinkPins().size() > 3) continue;
            for (SitePinInst sink : net.getSinkPins()) {
                if (!sink.isRouted() || sink.getTile() == net.getSource().getTile()) continue;
                out.add(sink);
                if (out.size() == count) return out;
                break;   // one sink per net
            }
        }
        return out;
    }

    /**
     * Delay-budgeted detours: routed connections of picoblaze are given a window of +150 to +600 ps
     * over their current delay, unrouted, and re-routed by HoldFixRouter; the routes must land in
     * the window (measured in the router's own units) and every pin must be routed.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testDelayBudgets() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven"});

        List<SitePinInst> sinks = pickSinks(design, 30);
        Assertions.assertTrue(sinks.size() >= 20, "only " + sinks.size() + " candidate sinks");

        RWRouteConfig config = new RWRouteConfig(new String[] {"--fixBoundingBox", "--useUTurnNodes", "--nonTimingDriven"});
        // no node-type exclusion: the budget alone must produce the detour
        HoldFixRouter router = new HoldFixRouter(design, config, sinks, false, new ArrayList<>());

        Map<SitePinInst, Float> before = new HashMap<>();
        Map<Net, List<SitePinInst>> byNet = new HashMap<>();
        for (SitePinInst sink : sinks) {
            float lb = router.routeDelay(sink.getNet(), sink);
            Assertions.assertFalse(Float.isNaN(lb), "no route delay for " + sink);
            before.put(sink, lb);
            router.setDelayBudget(sink, lb + 150f, lb + 600f, lb);
            byNet.computeIfAbsent(sink.getNet(), n -> new ArrayList<>()).add(sink);
        }
        for (Map.Entry<Net, List<SitePinInst>> e : byNet.entrySet()) {
            DesignTools.unroutePins(e.getKey(), e.getValue());
        }

        router.initialize();
        router.route();

        int inWindow = 0, under = 0, over = 0;
        StringBuilder report = new StringBuilder();
        for (SitePinInst sink : sinks) {
            Assertions.assertTrue(sink.isRouted(), sink + " not routed");
            Float achieved = router.getAchievedDelay(sink);
            Assertions.assertNotNull(achieved, sink + " has no budget outcome");
            Assertions.assertFalse(Float.isNaN(achieved), sink + " unrouted by the budgeted search");
            float lb = before.get(sink);
            report.append(String.format("%s: %.0f -> %.0f (budget %.0f..%.0f)%n", sink, lb, achieved, lb + 150f, lb + 600f));
            if (achieved < lb + 140f) under++;
            else if (achieved > lb + 610f) over++;
            else inWindow++;
        }
        System.out.print(report);
        System.out.println(router.describeBudgets());
        long[] stats = router.getBudgetedSearchStats();
        System.out.printf("budgeted search: %d popped, %d pushed, %d stale skipped; in window %d, under %d, over %d%n",
                stats[0], stats[1], stats[2], inWindow, under, over);
        // the fabric offers delay in coarse steps and congestion can block a detour; most must land in the window
        Assertions.assertTrue(inWindow >= 0.8 * sinks.size(), "only " + inWindow + " of " + sinks.size() + " in the window (" + under + " under, " + over + " over)");
        for (Net net : byNet.keySet()) {
            for (SitePinInst p : net.getPins()) {
                Assertions.assertTrue(p.isRouted(), p + " of " + net + " lost its route");
            }
        }
    }

    /** Without budgets the router behaves as before: the excluded node types are the series' quads and longs. */
    @Test
    public void testDefaultDisallowedNodeTypes() {
        Design design = new Design("t", "xcv80-lsva4737-2MHP-e-S");
        RWRouteConfig config = new RWRouteConfig(new String[] {"--nonTimingDriven"});
        HoldFixRouter router = new HoldFixRouter(design, config, new ArrayList<>());
        Assertions.assertEquals(6, router.getDisallowedNodeTypes().size());
        Assertions.assertTrue(router.getDelayBudgets().isEmpty());
    }
}
