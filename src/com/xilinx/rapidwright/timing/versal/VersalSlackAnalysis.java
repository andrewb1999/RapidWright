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

package com.xilinx.rapidwright.timing.versal;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Setup and hold slack of every register endpoint of a placed and routed Versal design, from the
 * data-path model ({@link VersalTimingModel}, {@link VersalTimingGraph}) and the clock model
 * ({@link VersalClockModel}), the way Vivado's report assembles it:
 * <pre>
 *   setup slack = period + capture clock (min corner) + pessimism removal - uncertainty - setup
 *               - (launch clock (max corner) + clock-to-Q + data path (max corner))
 *   hold slack  = launch clock (min corner) + clock-to-Q + data path (min corner)
 *               - (capture clock (max corner) - pessimism removal + hold uncertainty + hold)
 * </pre>
 * evaluated at the slow and the fast process; WNS and WHS are the minima over endpoints and
 * processes. Requires a model with all four corners.
 */
public class VersalSlackAnalysis {

    /** One endpoint's result at one process (slow or fast). */
    public static class Result {
        public VersalTimingGraph.Vertex endpoint;
        public VersalTimingGraph.Vertex launch;
        public boolean fast;
        public float setupSlack, holdSlack;
        public float launchClockMax, launchClockMin, captureClockMax, captureClockMin;
        public float setupPessimism, holdPessimism;
        /** pessimism removal by variant (see VersalClockModel.pessimism), for diagnostics */
        public float[] setupPessimismVariants = new float[6], holdPessimismVariants = new float[6];
        public float dataMax, dataMin;   // launch clock + clk-to-Q + data path, i.e. the arrival
        public float setupCheck, holdCheck;
    }

    private final Design design;
    private final VersalTimingModel model;
    private final VersalTimingGraph graph;
    private final VersalClockModel clockModel;
    private final float periodPs, setupUncertaintyPs, holdUncertaintyPs;
    private final Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
    private final Map<VersalTimingGraph.Vertex, SitePinInst> clockPinOf = new HashMap<>();
    private final int iSlowMax, iSlowMin, iFastMax, iFastMin;
    private int unclockedLaunches = 0, unclockedEndpoints = 0;
    private final List<Result> results = new ArrayList<>();
    private Result worstSetup, worstHold;

    public VersalSlackAnalysis(Design design, VersalTimingModel model, VersalClockModel clockModel,
                               float periodPs, float setupUncertaintyPs, float holdUncertaintyPs) {
        this.design = design;
        this.model = model;
        this.clockModel = clockModel;
        this.periodPs = periodPs;
        this.setupUncertaintyPs = setupUncertaintyPs;
        this.holdUncertaintyPs = holdUncertaintyPs;
        iSlowMax = model.indexOf(VersalCorner.SLOW_MAX);
        iSlowMin = model.indexOf(VersalCorner.SLOW_MIN);
        iFastMax = model.indexOf(VersalCorner.FAST_MAX);
        iFastMin = model.indexOf(VersalCorner.FAST_MIN);
        if (iSlowMax < 0 || iSlowMin < 0 || iFastMax < 0 || iFastMin < 0)
            throw new IllegalArgumentException("slack analysis needs a model with all four corners");
        graph = new VersalTimingGraph(design, model);
    }

    public float getSetupUncertainty() { return setupUncertaintyPs; }
    public float getHoldUncertainty() { return holdUncertaintyPs; }

    public VersalTimingGraph getGraph() {
        return graph;
    }

    public VersalClockModel.ClockTree getClockTree(Net net) {
        return trees.computeIfAbsent(net, n -> clockModel.analyze(n, model));
    }

    /** The clock net sink pin feeding a vertex's cell clock, or null. */
    public SitePinInst clockSitePin(VersalTimingGraph.Vertex v) {
        if (clockPinOf.containsKey(v)) return clockPinOf.get(v);
        SitePinInst spi = null;
        String phys = graph.getClockPin(v);
        Cell c = v.cell;
        if (phys != null) {
            String logical = c.getLogicalPinMapping(phys);
            if (logical != null) spi = c.getSitePinFromLogicalPin(logical, null);
            if (spi == null && c.getSiteInst() != null) {
                // hard blocks (DSP58, BRAM): the site pin carries the BEL pin's name, or the site has a single clock pin
                spi = c.getSiteInst().getSitePinInst(phys);
                if (spi == null || spi.getNet() == null || !VersalClockModel.isGlobalClockNet(spi.getNet())) {
                    spi = null;
                    for (SitePinInst p : c.getSiteInst().getSitePinInsts()) {
                        if (p.getNet() != null && VersalClockModel.isGlobalClockNet(p.getNet())) { spi = spi == null ? p : null; if (spi == null) break; }
                    }
                }
            }
        }
        if (spi != null && (spi.getNet() == null || !VersalClockModel.isGlobalClockNet(spi.getNet()))) spi = null;
        clockPinOf.put(v, spi);
        return spi;
    }

    private float[] clockArrival(SitePinInst spi, Cell cell) {
        if (spi == null) return null;
        VersalClockModel.ClockTree t = getClockTree(spi.getNet());
        return clockModel.pinArrival(t, spi, cell);
    }

    /** Builds the graph, seeds launches with clock arrivals, propagates, and computes every slack. */
    public void run() {
        graph.build();
        for (VersalTimingGraph.Vertex q : graph.getLaunches()) {
            float[] arr = clockArrival(clockSitePin(q), q.cell);
            if (arr == null) { unclockedLaunches++; graph.unseedLaunch(q); continue; }
            graph.seedLaunch(q, arr);
        }
        graph.computeArrivals();
        for (VersalTimingGraph.Vertex v : graph.getEndpoints()) {
            SitePinInst cap = clockSitePin(v);
            float[] capArr = clockArrival(cap, v.cell);
            if (capArr == null) { unclockedEndpoints++; continue; }
            for (boolean fast : new boolean[] {false, true}) {
                int iMax = fast ? iFastMax : iSlowMax, iMin = fast ? iFastMin : iSlowMin;
                if (Float.isInfinite(v.arrival[iMax]) || Float.isInfinite(v.arrival[iMin])) continue;
                Result r = new Result();
                r.endpoint = v;
                r.fast = fast;
                r.captureClockMax = capArr[iMax];
                r.captureClockMin = capArr[iMin];
                r.setupCheck = v.check[iMax];
                r.holdCheck = v.check[iMin];
                // setup: worst launch at the max corner
                List<VersalTimingGraph.Edge> path = graph.getPath(v, iMax);
                VersalTimingGraph.Vertex launch = path.isEmpty() ? v : path.get(0).src;
                r.launch = launch;
                SitePinInst lp = clockSitePin(launch);
                float[] lArr = clockArrival(lp, launch.cell);
                float[] cpr = lp != null && lp.getNet() == cap.getNet() ? clockModel.pessimism(getClockTree(cap.getNet()), lp, cap) : new float[2];
                r.launchClockMax = lArr == null ? 0 : lArr[iMax];
                r.setupPessimism = cpr[fast ? 1 : 0];
                if (lp != null && lp.getNet() == cap.getNet()) for (int vv = 0; vv < 6; vv++) r.setupPessimismVariants[vv] = clockModel.pessimism(getClockTree(cap.getNet()), lp, cap, vv)[fast ? 1 : 0];
                r.dataMax = v.arrival[iMax];
                r.setupSlack = periodPs + r.captureClockMin + r.setupPessimism - setupUncertaintyPs - r.setupCheck - r.dataMax;
                // hold: shortest launch at the min corner
                List<VersalTimingGraph.Edge> hpath = graph.getPath(v, iMin);
                VersalTimingGraph.Vertex hl = hpath.isEmpty() ? v : hpath.get(0).src;
                SitePinInst hlp = clockSitePin(hl);
                float[] hlArr = clockArrival(hlp, hl.cell);
                float[] hcpr = hlp != null && hlp.getNet() == cap.getNet() ? clockModel.holdPessimism(getClockTree(cap.getNet()), hlp, cap) : new float[2];
                r.launchClockMin = hlArr == null ? 0 : hlArr[iMin];
                r.holdPessimism = hcpr[fast ? 1 : 0];
                if (hlp != null && hlp.getNet() == cap.getNet()) for (int vv = 0; vv < 6; vv++) r.holdPessimismVariants[vv] = clockModel.pessimism(getClockTree(cap.getNet()), hlp, cap, vv, true)[fast ? 1 : 0];
                r.dataMin = v.arrival[iMin];
                r.holdSlack = r.dataMin - (r.captureClockMax - r.holdPessimism + holdUncertaintyPs + r.holdCheck);
                results.add(r);
                if (worstSetup == null || r.setupSlack < worstSetup.setupSlack) worstSetup = r;
                if (worstHold == null || r.holdSlack < worstHold.holdSlack) worstHold = r;
            }
        }
    }

    /**
     * Slack of one specific launch -> endpoint pair (the model's worst path between the two), for comparing
     * against a Vivado path with that startpoint; null if the endpoint is not reachable from the launch or
     * either flop is unclocked. Run {@link #run()} first.
     */
    public Result evaluatePair(VersalTimingGraph.Vertex launch, VersalTimingGraph.Vertex v, boolean fast) {
        SitePinInst cap = clockSitePin(v);
        float[] capArr = clockArrival(cap, v.cell);
        SitePinInst lp = clockSitePin(launch);
        float[] lArr = clockArrival(lp, launch.cell);
        if (capArr == null || lArr == null) return null;
        int iMax = fast ? iFastMax : iSlowMax, iMin = fast ? iFastMin : iSlowMin;
        List<VersalTimingGraph.Edge> path = graph.getPathFrom(launch, v, iMax), hpath = graph.getPathFrom(launch, v, iMin);
        if (path == null || hpath == null) return null;
        Result r = new Result();
        r.endpoint = v; r.launch = launch; r.fast = fast;
        r.captureClockMax = capArr[iMax]; r.captureClockMin = capArr[iMin];
        r.setupCheck = v.check[iMax]; r.holdCheck = v.check[iMin];
        boolean sameNet = lp != null && lp.getNet() == cap.getNet();
        float[] cpr = sameNet ? clockModel.pessimism(getClockTree(cap.getNet()), lp, cap) : new float[2];
        float[] hcpr = sameNet ? clockModel.holdPessimism(getClockTree(cap.getNet()), lp, cap) : new float[2];
        r.launchClockMax = lArr[iMax]; r.launchClockMin = lArr[iMin];
        r.setupPessimism = cpr[fast ? 1 : 0]; r.holdPessimism = hcpr[fast ? 1 : 0];
        r.dataMax = graph.pathArrival(path, v, iMax);
        r.dataMin = graph.pathArrival(hpath, v, iMin);
        r.setupSlack = periodPs + r.captureClockMin + r.setupPessimism - setupUncertaintyPs - r.setupCheck - r.dataMax;
        r.holdSlack = r.dataMin - (r.captureClockMax - r.holdPessimism + holdUncertaintyPs + r.holdCheck);
        return r;
    }

    /** {@link #describe} for a pair result: the path from that launch rather than the endpoint's worst path. */
    public String describePair(Result r, boolean setup) {
        int iMax = r.fast ? iFastMax : iSlowMax, iMin = r.fast ? iFastMin : iSlowMin;
        List<VersalTimingGraph.Edge> path = graph.getPathFrom(r.launch, r.endpoint, setup ? iMax : iMin);
        StringBuilder sb = new StringBuilder();
        if (setup) sb.append(String.format("setup slack %.0f ps (%s process): launch %s clock %.0f + data %.0f = arrival %.0f; required = %.0f + capture %.0f + cpr %.0f - unc %.0f - setup %.0f%n",
                r.setupSlack, r.fast ? "fast" : "slow", r.launch, r.launchClockMax, r.dataMax - r.launchClockMax, r.dataMax, periodPs, r.captureClockMin, r.setupPessimism, setupUncertaintyPs, r.setupCheck));
        else sb.append(String.format("hold slack %.0f ps (%s process): launch clock %.0f + data %.0f = arrival %.0f; required = capture %.0f - cpr %.0f + unc %.0f + hold %.0f%n",
                r.holdSlack, r.fast ? "fast" : "slow", r.launchClockMin, r.dataMin - r.launchClockMin, r.dataMin, r.captureClockMax, r.holdPessimism, holdUncertaintyPs, r.holdCheck));
        sb.append(graph.formatPath(path, r.endpoint, setup ? iMax : iMin));
        return sb.toString();
    }

    public List<Result> getResults() {
        return results;
    }

    public Result getWorstSetup() {
        return worstSetup;
    }

    public Result getWorstHold() {
        return worstHold;
    }

    /** Worst negative slack (ps): the minimum setup slack over all endpoints and both processes. */
    public float getWNS() {
        return worstSetup == null ? Float.NaN : worstSetup.setupSlack;
    }

    /** Worst hold slack (ps). */
    public float getWHS() {
        return worstHold == null ? Float.NaN : worstHold.holdSlack;
    }

    public int getUnclockedLaunchCount() { return unclockedLaunches; }
    public int getUnclockedEndpointCount() { return unclockedEndpoints; }

    public String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("period %.0f ps, setup uncertainty %.0f ps, hold uncertainty %.0f ps; %d endpoints x 2 processes; unclocked launches %d, endpoints %d%n",
                periodPs, setupUncertaintyPs, holdUncertaintyPs, results.size() / 2, unclockedLaunches, unclockedEndpoints));
        for (Net n : trees.keySet()) sb.append("clock net ").append(n.getName()).append(": ").append(trees.get(n).sinkArrival.size()).append(" sinks\n");
        for (Map.Entry<Net, String> e : clockModel.getRootDescriptions().entrySet()) sb.append("  root of ").append(e.getKey().getName()).append(": ").append(e.getValue()).append('\n');
        sb.append("clock model tiers: ").append(clockModel.getTierUse()).append(", intra-site misses ").append(clockModel.getSiteMissCount()).append('\n');
        if (worstSetup != null) sb.append(describe(worstSetup, true));
        if (worstHold != null) sb.append(describe(worstHold, false));
        return sb.toString();
    }

    public String describe(Result r, boolean setup) {
        int iMax = r.fast ? iFastMax : iSlowMax, iMin = r.fast ? iFastMin : iSlowMin;
        StringBuilder sb = new StringBuilder();
        if (setup) {
            sb.append(String.format("WNS %.0f ps at %s (%s process): launch %s clock %.0f + data %.0f = arrival %.0f; required = %.0f + capture %.0f + cpr %.0f - unc %.0f - setup %.0f%n",
                    r.setupSlack, r.endpoint, r.fast ? "fast" : "slow", r.launch, r.launchClockMax, r.dataMax - r.launchClockMax, r.dataMax,
                    periodPs, r.captureClockMin, r.setupPessimism, setupUncertaintyPs, r.setupCheck));
            sb.append(graph.formatPath(r.endpoint, iMax));
        } else {
            sb.append(String.format("WHS %.0f ps at %s (%s process): launch clock %.0f + data %.0f = arrival %.0f; required = capture %.0f - cpr %.0f + unc %.0f + hold %.0f%n",
                    r.holdSlack, r.endpoint, r.fast ? "fast" : "slow", r.launchClockMin, r.dataMin - r.launchClockMin, r.dataMin,
                    r.captureClockMax, r.holdPessimism, holdUncertaintyPs, r.holdCheck));
            sb.append(graph.formatPath(r.endpoint, iMin));
        }
        return sb.toString();
    }
}
