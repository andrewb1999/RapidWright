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
 */

package com.xilinx.rapidwright.timing.versal;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-alone timing report for a placed and routed Versal design: WNS, WHS, TNS/THS and the
 * worst endpoints with their clock skew, from the lightweight timing model alone (no Vivado).
 *
 * <pre>
 * VersalTimingReport design.dcp [design.edf] [--period ns] [--setup-uncertainty ps]
 *     [--hold-uncertainty ps] [--top N] [--csv slack.csv] [--leaf-delays x.leafdelay.csv]
 * </pre>
 *
 * The clock period defaults to the {@code create_clock -period} of the checkpoint's XDC
 * constraints. Vivado's default clock uncertainty is jitter-derived (46-109 ps observed on the
 * training designs, hold 0); it is not modelled, so pass {@code --setup-uncertainty} to match a
 * particular report. Slack is assembled as in {@link VersalSlackAnalysis}: register-to-register
 * paths, launch clock at the max corner, capture at the min corner, clock pessimism removal on
 * the shared clock path, at both the slow and the fast process.
 */
public class VersalTimingReport {

    private static final Pattern CREATE_CLOCK = Pattern.compile("create_clock\\s.*?-period\\s+([0-9.]+)");

    /** The first create_clock period in the design's XDC constraints, in ps, or 0 if none. */
    public static float periodFromConstraints(Design design) {
        float first = 0;
        for (ConstraintGroup g : ConstraintGroup.values()) {
            List<String> xdc = design.getXDCConstraints(g);
            if (xdc == null) continue;
            for (String line : xdc) {
                Matcher m = CREATE_CLOCK.matcher(line);
                if (!m.find()) continue;
                float p = Float.parseFloat(m.group(1)) * 1000f;
                if (first == 0) first = p;
                else if (Math.abs(p - first) > 0.5f)
                    System.out.println("WARNING: several create_clock periods (" + first + " and " + p + " ps); using the first");
            }
        }
        return first;
    }

    /** Graph vertex of "cell/logicalPin" (the pin is mapped to the physical BEL pin), or null. */
    static VersalTimingGraph.Vertex vertexOf(Design design, VersalTimingGraph g, String cellPin) {
        int slash = cellPin.lastIndexOf('/');
        if (slash < 0) return null;
        com.xilinx.rapidwright.design.Cell cell = design.getCell(cellPin.substring(0, slash));
        if (cell == null) return null;
        String phys = cell.getPhysicalPinMapping(cellPin.substring(slash + 1));
        return phys == null ? g.getVertex(cell, cellPin.substring(slash + 1)) : g.getVertex(cell, phys);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args[0].startsWith("--")) {
            System.out.println("Usage: VersalTimingReport design.dcp [design.edf] [--period ns] [--setup-uncertainty ps] [--hold-uncertainty ps] [--top N] [--csv slack.csv] [--leaf-delays csv]");
            return;
        }
        String dcp = args[0];
        String edf = null;
        float periodPs = 0, setupUnc = 0, holdUnc = 0;
        int top = 10;
        String csv = null, leafCsv = null;
        List<String> pairs = new ArrayList<>();
        int i = 1;
        if (i < args.length && !args[i].startsWith("--")) edf = args[i++];
        for (; i < args.length; i++) {
            switch (args[i]) {
                case "--period": periodPs = Float.parseFloat(args[++i]) * 1000f; break;
                case "--setup-uncertainty": setupUnc = Float.parseFloat(args[++i]); break;
                case "--hold-uncertainty": holdUnc = Float.parseFloat(args[++i]); break;
                case "--top": top = Integer.parseInt(args[++i]); break;
                case "--csv": csv = args[++i]; break;
                case "--leaf-delays": leafCsv = args[++i]; break;
                case "--pair": pairs.add(args[++i]); break;   // "launchcell/Q->endcell/D": the model's slack for that specific path
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }

        long t0 = System.currentTimeMillis();
        Design design = edf != null && new File(edf).exists() ? Design.readCheckpoint(dcp, edf) : Design.readCheckpoint(dcp);
        // checkpoints assembled by RapidWright can lack site pin instances on hard blocks (DSP58 CLK)
        int before = 0;
        for (SiteInst si : design.getSiteInsts()) before += si.getSitePinInstMap().size();
        DesignTools.createMissingSitePinInsts(design);
        int after = 0;
        for (SiteInst si : design.getSiteInsts()) after += si.getSitePinInstMap().size();
        System.out.println("loaded " + design.getName() + " on " + design.getPartName() + " in " + (System.currentTimeMillis() - t0) + " ms"
                + (after != before ? " (created " + (after - before) + " missing site pin instances)" : ""));

        if (periodPs <= 0) periodPs = periodFromConstraints(design);
        if (periodPs <= 0) throw new IllegalArgumentException("no create_clock -period in the checkpoint's constraints; pass --period <ns>");

        t0 = System.currentTimeMillis();
        VersalTimingModel model = new VersalTimingModel(design.getDevice());
        VersalClockModel cm = new VersalClockModel();
        if (leafCsv == null) { String c = dcp.replaceAll("\\.dcp$", "") + ".leafdelay.csv"; if (new File(c).exists()) leafCsv = c; }
        if (leafCsv != null) { cm.loadLeafDelays(leafCsv); System.out.println("clock model: " + cm.getLeafDelayCount() + " programmed leaf delays from " + leafCsv); }
        System.out.println("models loaded in " + (System.currentTimeMillis() - t0) + " ms (" + cm.getContextCount() + " clock contexts)");

        t0 = System.currentTimeMillis();
        VersalSlackAnalysis sa = new VersalSlackAnalysis(design, model, cm, periodPs, setupUnc, holdUnc);
        sa.run();
        VersalTimingGraph g = sa.getGraph();
        System.out.printf("graph: %d vertices, %d edges, built and analysed in %d ms; unknown BELs %d %s, unrouted sinks %d, skipped nets %d, edge misses %d, intra-site misses %d/%d%n",
                g.getVertexCount(), g.getEdgeCount(), System.currentTimeMillis() - t0, g.getUnknownBelCount(), g.getUnknownBelNames(),
                g.getUnroutedSinkCount(), g.getSkippedNetCount(), model.getEdgeMissCount(), model.getIntraSiteMissCount(), model.getIntraSiteLookupCount());
        if (model.getIntraSiteMissCount() > 0) {
            List<Map.Entry<String, Integer>> miss = new ArrayList<>(model.getIntraSiteMissKeys().entrySet());
            miss.sort((a, b) -> b.getValue() - a.getValue());
            System.out.println("intra-site misses (" + model.getIntraSiteMissCount() + "), most frequent keys:");
            for (int k = 0; k < Math.min(25, miss.size()); k++) System.out.println("   " + miss.get(k).getValue() + "x " + miss.get(k).getKey());
        }
        System.out.print(sa.report());

        // per endpoint: the worse of the two processes, as Vivado's summary counts endpoints
        Map<VersalTimingGraph.Vertex, VersalSlackAnalysis.Result> worstSetup = new HashMap<>(), worstHold = new HashMap<>();
        for (VersalSlackAnalysis.Result r : sa.getResults()) {
            VersalSlackAnalysis.Result s = worstSetup.get(r.endpoint);
            if (s == null || r.setupSlack < s.setupSlack) worstSetup.put(r.endpoint, r);
            VersalSlackAnalysis.Result h = worstHold.get(r.endpoint);
            if (h == null || r.holdSlack < h.holdSlack) worstHold.put(r.endpoint, r);
        }
        List<VersalSlackAnalysis.Result> setup = new ArrayList<>(worstSetup.values()), hold = new ArrayList<>(worstHold.values());
        setup.sort(Comparator.comparingDouble(r -> r.setupSlack));
        hold.sort(Comparator.comparingDouble(r -> r.holdSlack));
        double tns = 0, ths = 0;
        int nSetupFail = 0, nHoldFail = 0;
        for (VersalSlackAnalysis.Result r : setup) if (r.setupSlack < 0) { tns += r.setupSlack; nSetupFail++; }
        for (VersalSlackAnalysis.Result r : hold) if (r.holdSlack < 0) { ths += r.holdSlack; nHoldFail++; }

        System.out.println();
        System.out.println("==================== Design Timing Summary (model) ====================");
        System.out.printf("period %.3f ns, setup uncertainty %.0f ps, hold uncertainty %.0f ps, %d endpoints%n", periodPs / 1000f, setupUnc, holdUnc, setup.size());
        System.out.printf("WNS %8.3f ns   TNS %10.3f ns   failing endpoints %d%n", sa.getWNS() / 1000f, tns / 1000, nSetupFail);
        System.out.printf("WHS %8.3f ns   THS %10.3f ns   failing endpoints %d%n", sa.getWHS() / 1000f, ths / 1000, nHoldFail);
        System.out.println();

        System.out.println("worst " + Math.min(top, setup.size()) + " setup endpoints (ps; skew = capture clock - launch clock + pessimism removal, as Vivado reports it):");
        System.out.printf("%8s %6s %8s %8s %6s %6s %8s %6s  %s <- %s%n", "slack", "proc", "launch", "capture", "cpr", "skew", "data", "setup", "endpoint", "startpoint");
        for (int k = 0; k < Math.min(top, setup.size()); k++) {
            VersalSlackAnalysis.Result r = setup.get(k);
            float skew = r.captureClockMin - r.launchClockMax + r.setupPessimism;
            System.out.printf("%8.0f %6s %8.0f %8.0f %6.0f %6.0f %8.0f %6.0f  %s <- %s%n", r.setupSlack, r.fast ? "fast" : "slow", r.launchClockMax, r.captureClockMin,
                    r.setupPessimism, skew, r.dataMax - r.launchClockMax, r.setupCheck, r.endpoint, r.launch);
        }
        System.out.println();
        System.out.println("worst " + Math.min(top, hold.size()) + " hold endpoints (ps; skew = capture clock - launch clock - pessimism removal):");
        System.out.printf("%8s %6s %8s %8s %6s %6s %8s %6s  %s <- %s%n", "slack", "proc", "launch", "capture", "cpr", "skew", "data", "hold", "endpoint", "startpoint");
        for (int k = 0; k < Math.min(top, hold.size()); k++) {
            VersalSlackAnalysis.Result r = hold.get(k);
            float skew = r.captureClockMax - r.launchClockMin - r.holdPessimism;
            System.out.printf("%8.0f %6s %8.0f %8.0f %6.0f %6.0f %8.0f %6.0f  %s <- %s%n", r.holdSlack, r.fast ? "fast" : "slow", r.launchClockMin, r.captureClockMax,
                    r.holdPessimism, skew, r.dataMin - r.launchClockMin, r.holdCheck, r.endpoint, r.holdLaunch);
        }

        for (String pr : pairs) {
            String[] pp = pr.split("->");
            VersalTimingGraph.Vertex l = vertexOf(design, g, pp[0]), e = vertexOf(design, g, pp[1]);
            System.out.println();
            System.out.println("pair " + pr + ":");
            if (l == null || e == null) { System.out.println("  not in graph: " + (l == null ? pp[0] : pp[1])); continue; }
            for (boolean fast : new boolean[] {false, true}) {
                VersalSlackAnalysis.Result r = sa.evaluatePair(l, e, fast);
                if (r == null) { System.out.println("  no path from " + l + " to " + e + " (" + (fast ? "fast" : "slow") + ")"); continue; }
                System.out.print(sa.describePair(r, true));
                System.out.print(sa.describePair(r, false));
            }
        }

        if (csv != null) {
            try (PrintWriter pw = new PrintWriter(csv)) {
                pw.println("endpoint,startpoint,process,setup_slack_ps,hold_slack_ps,launch_clock_max_ps,launch_clock_min_ps,capture_clock_max_ps,capture_clock_min_ps,setup_cpr_ps,hold_cpr_ps,data_max_ps,data_min_ps,setup_check_ps,hold_check_ps,hold_startpoint");
                for (VersalSlackAnalysis.Result r : sa.getResults())
                    pw.printf("%s,%s,%s,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%.1f,%s%n", r.endpoint, r.launch, r.fast ? "fast" : "slow",
                            r.setupSlack, r.holdSlack, r.launchClockMax, r.launchClockMin, r.captureClockMax, r.captureClockMin,
                            r.setupPessimism, r.holdPessimism, r.dataMax - r.launchClockMax, r.dataMin - r.launchClockMin, r.setupCheck, r.holdCheck, r.holdLaunch);
            }
            System.out.println("wrote " + sa.getResults().size() + " rows to " + csv);
        }
    }
}
