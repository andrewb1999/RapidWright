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
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * <p>
 * The pessimism removal depends on the launch (how much clock path it shares with the capture), so
 * the launch with the extreme arrival is not always the one with the worst slack: a flop in the
 * capture's own tile can arrive 9 ps earlier than one in the next clock region and still have 100 ps
 * more hold slack. Arrivals are therefore propagated per launch clock group (the launch's clock leaf
 * node, {@link VersalTimingGraph.Tagged}) and each endpoint takes the worst slack over its groups,
 * as a tagged STA engine does.
 */
public class VersalSlackAnalysis {

    /** One endpoint's result at one process (slow or fast). */
    public static class Result {
        public VersalTimingGraph.Vertex endpoint;
        /** the launch of the worst setup path */
        public VersalTimingGraph.Vertex launch;
        /** the launch of the worst hold path (may differ: the pessimism removal depends on the launch) */
        public VersalTimingGraph.Vertex holdLaunch;
        /** the arrival groups (VersalTimingGraph.Tagged tags) the worst setup / hold paths belong to */
        public Object setupTag, holdTag;
        public boolean fast;
        public float setupSlack, holdSlack;
        public float launchClockMax, launchClockMin, captureClockMax, captureClockMin;
        public float setupPessimism, holdPessimism;
        /** pessimism removal by variant (see VersalClockModel.pessimism), for diagnostics */
        public float[] setupPessimismVariants = new float[6], holdPessimismVariants = new float[6];
        public float dataMax, dataMin;   // launch clock + clk-to-Q + data path, i.e. the arrival
        public float setupCheck, holdCheck;
        /** Vivado's inter-SLR compensation (ps) when the data path crosses an SLR: subtracted from the setup and the hold slack */
        public float setupSlrComp, holdSlrComp;
    }

    /**
     * Vivado's inter-SLR compensation prorating factor ("Prorating Factor (PF)" in report_timing, 0.100 on the
     * xcv80 -2MHP): a path whose launch and capture cells sit in different SLRs loses
     * {@code (capture clock (min) - common clock delay) * PF} of setup slack and
     * {@code (launch clock (min) + data (min) - common clock delay) * PF} of hold slack, the common clock
     * delay being the arrival at the nearest common node of the two clock branches (RapidSA 32x8, Sep 11:
     * 358 / 229 ps setup, 401 ps hold on its SLR-crossing register pairs, which set Vivado's WNS and WHS).
     */
    public static final float SLR_PRORATING = 0.1f;

    private static boolean crossesSlr(VersalTimingGraph.Vertex launch, VersalTimingGraph.Vertex v) {
        return VersalClockArrivals.crossesSlr(launch, v);
    }

    private float[] commonDelayMin(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap) {
        return clocks.commonDelayMin(tree, lp, cap);
    }

    private final Design design;
    private final VersalTimingModel model;
    private final VersalTimingGraph graph;
    private final VersalClockModel clockModel;
    private final float periodPs, setupUncertaintyPs, holdUncertaintyPs;
    /** clock trees, clock pins, arrivals and pessimism (shared with an analysis derived from this one) */
    private final VersalClockArrivals clocks;
    private final int iSlowMax, iSlowMin, iFastMax, iFastMin;
    private int unclockedLaunches = 0, unclockedEndpoints = 0;
    /** per endpoint, its {slow, fast} results (an endpoint with no result at a process is absent) */
    private final Map<VersalTimingGraph.Vertex, Result[]> byEndpoint = new LinkedHashMap<>();
    private List<Result> results = null;   // flattened byEndpoint, rebuilt on demand
    private Result worstSetup, worstHold;
    private boolean analysed = false;

    public VersalSlackAnalysis(Design design, VersalTimingModel model, VersalClockModel clockModel,
                               float periodPs, float setupUncertaintyPs, float holdUncertaintyPs) {
        this(design, model, clockModel, new VersalTimingGraph(design, model), periodPs, setupUncertaintyPs, holdUncertaintyPs, null);
    }

    /**
     * An analysis with other uncertainties over an existing analysis's graph and clock caches (the
     * data-path arrivals and clock arrivals do not depend on the uncertainties): its {@link #run()}
     * skips the build and, when the base's arrivals are current, the propagation as well. The two
     * analyses share the graph, so only one of them may be updated from then on.
     */
    public VersalSlackAnalysis(VersalSlackAnalysis base, float setupUncertaintyPs, float holdUncertaintyPs) {
        this(base.design, base.model, base.clockModel, base.graph, base.periodPs, setupUncertaintyPs, holdUncertaintyPs, base.clocks);
        unclockedLaunches = base.unclockedLaunches;
        unclockedEndpoints = base.unclockedEndpoints;
    }

    private VersalSlackAnalysis(Design design, VersalTimingModel model, VersalClockModel clockModel, VersalTimingGraph graph,
                                float periodPs, float setupUncertaintyPs, float holdUncertaintyPs, VersalClockArrivals clocks) {
        this.design = design;
        this.model = model;
        this.clockModel = clockModel;
        this.graph = graph;
        this.periodPs = periodPs;
        this.setupUncertaintyPs = setupUncertaintyPs;
        this.holdUncertaintyPs = holdUncertaintyPs;
        this.clocks = clocks != null ? clocks : new VersalClockArrivals(graph, model, clockModel);
        iSlowMax = model.indexOf(VersalCorner.SLOW_MAX);
        iSlowMin = model.indexOf(VersalCorner.SLOW_MIN);
        iFastMax = model.indexOf(VersalCorner.FAST_MAX);
        iFastMin = model.indexOf(VersalCorner.FAST_MIN);
        if (iSlowMax < 0 || iSlowMin < 0 || iFastMax < 0 || iFastMin < 0)
            throw new IllegalArgumentException("slack analysis needs a model with all four corners");
    }

    public float getSetupUncertainty() { return setupUncertaintyPs; }
    public float getHoldUncertainty() { return holdUncertaintyPs; }

    public VersalTimingGraph getGraph() {
        return graph;
    }

    public VersalClockModel.ClockTree getClockTree(Net net) {
        return clocks.getClockTree(net);
    }

    /** The clock net sink pin feeding a vertex's cell clock, or null. */
    public SitePinInst clockSitePin(VersalTimingGraph.Vertex v) {
        return clocks.clockSitePin(v);
    }

    /** Clock arrival (clock corners) at a site pin for the cell it clocks, or null. */
    private float[] clockArrival(SitePinInst spi, Cell cell) {
        return clocks.clockArrival(spi, cell);
    }

    /** The clock group of a launch: the clock-tree node feeding its clock site pin ({@link VersalClockArrivals#launchGroup}). */
    private Object launchGroup(SitePinInst spi) {
        return clocks.launchGroup(spi);
    }

    /**
     * Builds the graph, seeds launches with clock arrivals (one arrival group per launch clock leaf),
     * propagates, and computes every slack: per endpoint and process the worst slack over the groups,
     * each with the pessimism removal of its own launch.
     */
    /** Wall time of the analysis phases after the build: clock seeding, arrival propagation, slack (ms). */
    public final long[] phaseMs = new long[3];
    /** Wall time of the last {@link #update()}'s phases: net refresh, clock leaves, propagation, slack (ms). */
    public final long[] updateMs = new long[4];
    /** What the last {@link #update()} did: nets re-planned, clock sites changed, cone vertices, endpoints re-timed. */
    public final int[] updateCounts = new int[4];
    /** VERSAL_TIMING_VERIFY set: every {@link #update()} is checked against a fresh full analysis (memory for two graphs needed) */
    private static final boolean VERIFY = System.getenv("VERSAL_TIMING_VERIFY") != null;

    /**
     * The full analysis: builds the graph if this analysis (or the one it shares the graph with) has not,
     * seeds every launch with its clock arrival, propagates, and times every endpoint. On a graph whose
     * arrivals are already current (a second analysis with other uncertainties) only the slacks are computed.
     */
    public void run() {
        if (!graph.isBuilt()) graph.build();
        if (!graph.hasArrivals()) {
            long t = System.currentTimeMillis();
            graph.resetArrivals();
            unclockedLaunches = 0;
            for (VersalTimingGraph.Vertex q : graph.getLaunches()) if (!seed(q)) unclockedLaunches++;
            phaseMs[0] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
            graph.computeArrivals();
            phaseMs[1] = System.currentTimeMillis() - t;
        }
        long t = System.currentTimeMillis();
        byEndpoint.clear();
        unclockedEndpoints = 0;
        for (VersalTimingGraph.Vertex v : graph.getEndpoints()) {
            Result[] r = computeSlack(v);
            if (r != null) byEndpoint.put(v, r);
        }
        finishResults();
        phaseMs[2] = System.currentTimeMillis() - t;
        analysed = true;
    }

    /**
     * Brings the analysis up to date with the design after nets were rerouted or slices' leaf clock delays
     * changed, re-timing only what those changes reach: the changed nets' edges are replaced, the launches
     * of the changed slices re-seeded, arrivals re-propagated through the cone downstream of both, and the
     * endpoints in that cone or in a changed slice re-timed. Everything else keeps its result. Runs the full
     * analysis when none has run yet.
     */
    public void update() {
        if (!analysed) { run(); return; }
        long t = System.currentTimeMillis();
        Set<VersalTimingGraph.Vertex> touched = graph.refreshNets();
        updateCounts[0] = graph.getLastNetsRefreshed();
        updateMs[0] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        Set<Site> sites = graph.refreshClockLeaves();
        Set<VersalTimingGraph.Vertex> retime = new HashSet<>();
        if (!sites.isEmpty()) {
            clocks.invalidateSites(sites);
            for (VersalTimingGraph.Vertex q : graph.getLaunches()) if (sites.contains(q.cell.getSite())) { seed(q); touched.add(q); }
            for (VersalTimingGraph.Vertex v : graph.getEndpoints()) if (sites.contains(v.cell.getSite())) retime.add(v);
        }
        updateCounts[1] = sites.size();
        updateMs[1] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        Set<VersalTimingGraph.Vertex> cone = graph.propagateFrom(touched);
        for (VersalTimingGraph.Vertex v : cone) if (v.endpoint) retime.add(v);
        updateCounts[2] = cone.size();
        updateMs[2] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        for (VersalTimingGraph.Vertex v : retime) {
            Result[] r = computeSlack(v);
            if (r == null) byEndpoint.remove(v); else byEndpoint.put(v, r);
        }
        finishResults();
        updateCounts[3] = retime.size();
        updateMs[3] = System.currentTimeMillis() - t;
        if (VERIFY) verifyAgainstFull();
    }

    /** One line on what the last {@link #update()} did and what it cost. */
    public String describeUpdate() {
        return String.format("incremental: %d nets re-planned, %d clock sites changed, cone %d vertices, %d endpoints re-timed (refresh %d, clock %d, propagate %d, slack %d ms)",
                updateCounts[0], updateCounts[1], updateCounts[2], updateCounts[3], updateMs[0], updateMs[1], updateMs[2], updateMs[3]);
    }

    /** Debug check: a fresh full analysis of the same design must give every endpoint the same slacks. */
    private void verifyAgainstFull() {
        VersalSlackAnalysis full = new VersalSlackAnalysis(design, model, clockModel, periodPs, setupUncertaintyPs, holdUncertaintyPs);
        full.run();
        Map<String, float[]> mine = new HashMap<>(), theirs = new HashMap<>();
        for (Result r : getResults()) mine.put(r.endpoint.getName() + (r.fast ? "/f" : "/s"), new float[] {r.setupSlack, r.holdSlack});
        for (Result r : full.getResults()) theirs.put(r.endpoint.getName() + (r.fast ? "/f" : "/s"), new float[] {r.setupSlack, r.holdSlack});
        float worst = 0; String where = null; int mismatches = 0, shown = 0;
        for (Map.Entry<String, float[]> e : theirs.entrySet()) {
            float[] m = mine.get(e.getKey());
            float d = m == null ? Float.POSITIVE_INFINITY : Math.max(Math.abs(m[0] - e.getValue()[0]), Math.abs(m[1] - e.getValue()[1]));
            if (d > 0.5f) {
                mismatches++;
                if (shown++ < 5) System.out.printf("[Timing verify]   %s: incremental %s, full %s%n", e.getKey(), m == null ? "missing" : String.format("setup %.1f hold %.1f", m[0], m[1]), String.format("setup %.1f hold %.1f", e.getValue()[0], e.getValue()[1]));
            }
            if (d > worst && !Float.isInfinite(d)) { worst = d; where = e.getKey(); }
        }
        int extra = 0;
        for (String k : mine.keySet()) if (!theirs.containsKey(k)) extra++;
        System.out.printf("[Timing verify] %d endpoints: %d differ by more than 0.5 ps, %d only in the incremental result; worst %.2f ps at %s; WNS %.1f vs %.1f, WHS %.1f vs %.1f%n",
                theirs.size(), mismatches, extra, worst, where, getWNS(), full.getWNS(), getWHS(), full.getWHS());
    }

    /** Seeds a launch with its clock arrival (or unseeds it when its clock is unknown); returns whether it is clocked. */
    private boolean seed(VersalTimingGraph.Vertex q) {
        SitePinInst spi = clockSitePin(q);
        float[] arr = clockArrival(spi, q.cell);
        if (arr == null) { graph.unseedLaunch(q); return false; }
        graph.seedLaunch(q, arr, launchGroup(spi));
        return true;
    }

    /** Rebuilds the worst results and invalidates the flattened list after the per-endpoint results changed. */
    private void finishResults() {
        results = null;
        worstSetup = null; worstHold = null;
        for (Result[] rs : byEndpoint.values()) for (Result r : rs) {
            if (r == null) continue;
            if (worstSetup == null || r.setupSlack < worstSetup.setupSlack) worstSetup = r;
            if (worstHold == null || r.holdSlack < worstHold.holdSlack) worstHold = r;
        }
    }

    /**
     * Times one endpoint at both processes from the current arrivals: per process the worst slack over
     * the arrival groups, each with the pessimism removal of its own launch. Null when the endpoint is
     * unclocked or unreached; otherwise {slow, fast} with a null entry for a process without a result.
     */
    private Result[] computeSlack(VersalTimingGraph.Vertex v) {
        SitePinInst cap = clockSitePin(v);
        float[] capArr = clockArrival(cap, v.cell);
        if (capArr == null) { unclockedEndpoints++; return null; }
        List<VersalTimingGraph.Tagged> tags = VersalTimingGraph.getTags(v);
        if (tags.isEmpty()) return null;
        VersalClockModel.ClockTree tree = getClockTree(cap.getNet());
        Map<VersalTimingGraph.Vertex, float[][]> cprOf = new HashMap<>();   // launch -> {setup {slow, fast}, hold {slow, fast}}
        Result[] out = new Result[2];
        boolean any = false;
        for (boolean fast : new boolean[] {false, true}) {
            int iMax = fast ? iFastMax : iSlowMax, iMin = fast ? iFastMin : iSlowMin;
            Result r = new Result();
            r.endpoint = v;
            r.fast = fast;
            r.captureClockMax = capArr[iMax];
            r.captureClockMin = capArr[iMin];
            r.setupCheck = v.check[iMax];
            r.holdCheck = v.check[iMin];
            r.setupSlack = Float.POSITIVE_INFINITY;
            r.holdSlack = Float.POSITIVE_INFINITY;
            for (VersalTimingGraph.Tagged tg : tags) {
                // setup: the group's latest arrival at the max corner, with its launch's pessimism removal
                if (!Float.isInfinite(tg.arrival[iMax])) {
                    VersalTimingGraph.Vertex launch = launchOf(v, iMax, tg.tag);
                    float[][] cpr = cprOf.computeIfAbsent(launch, l -> pessimismOf(tree, l, clockSitePin(l), cap, capArr));
                    float slr = crossesSlr(launch, v) ? (r.captureClockMin - commonDelayMin(tree, clockSitePin(launch), cap)[fast ? 1 : 0]) * SLR_PRORATING : 0;
                    float slack = periodPs + r.captureClockMin + cpr[0][fast ? 1 : 0] - setupUncertaintyPs - r.setupCheck - tg.arrival[iMax] - slr;
                    if (slack < r.setupSlack) {
                        r.setupSlack = slack; r.launch = launch; r.setupTag = tg.tag; r.dataMax = tg.arrival[iMax]; r.setupPessimism = cpr[0][fast ? 1 : 0]; r.setupSlrComp = slr;
                    }
                }
                // hold: the group's earliest arrival at the min corner
                if (!Float.isInfinite(tg.arrival[iMin])) {
                    VersalTimingGraph.Vertex launch = launchOf(v, iMin, tg.tag);
                    float[][] cpr = cprOf.computeIfAbsent(launch, l -> pessimismOf(tree, l, clockSitePin(l), cap, capArr));
                    float slr = crossesSlr(launch, v) ? (tg.arrival[iMin] - commonDelayMin(tree, clockSitePin(launch), cap)[fast ? 1 : 0]) * SLR_PRORATING : 0;
                    float slack = tg.arrival[iMin] - (r.captureClockMax - cpr[1][fast ? 1 : 0] + holdUncertaintyPs + r.holdCheck) - slr;
                    if (slack < r.holdSlack) {
                        r.holdSlack = slack; r.holdLaunch = launch; r.holdTag = tg.tag; r.dataMin = tg.arrival[iMin]; r.holdPessimism = cpr[1][fast ? 1 : 0]; r.holdSlrComp = slr;
                    }
                }
            }
            if (Float.isInfinite(r.setupSlack) || Float.isInfinite(r.holdSlack)) continue;
            SitePinInst lp = clockSitePin(r.launch);
            float[] lArr = clockArrival(lp, r.launch.cell);
            r.launchClockMax = lArr == null ? 0 : lArr[iMax];
            if (lp != null && lp.getNet() == cap.getNet()) { float[][] pv = pairVariants(tree, lp, cap, false); for (int vv = 0; vv < 6; vv++) r.setupPessimismVariants[vv] = pv[vv][fast ? 1 : 0]; }
            SitePinInst hlp = clockSitePin(r.holdLaunch);
            float[] hlArr = clockArrival(hlp, r.holdLaunch.cell);
            r.launchClockMin = hlArr == null ? 0 : hlArr[iMin];
            if (hlp != null && hlp.getNet() == cap.getNet()) { float[][] pv = pairVariants(tree, hlp, cap, true); for (int vv = 0; vv < 6; vv++) r.holdPessimismVariants[vv] = pv[vv][fast ? 1 : 0]; }
            out[fast ? 1 : 0] = r;
            any = true;
        }
        return any ? out : null;
    }

    /** The launch at the head of an arrival group's path into an endpoint at a corner (the endpoint itself if none). */
    private VersalTimingGraph.Vertex launchOf(VersalTimingGraph.Vertex v, int corner, Object tag) {
        List<VersalTimingGraph.Edge> path = graph.getPath(v, corner, tag);
        return path.isEmpty() ? v : path.get(0).src;
    }

    /** {setup {slow, fast}, hold {slow, fast}} pessimism removal between a launch and a capture pin ({@link VersalClockArrivals#pessimismOf}). */
    private float[][] pessimismOf(VersalClockModel.ClockTree tree, VersalTimingGraph.Vertex launch, SitePinInst lp, SitePinInst cap, float[] capArr) {
        return clocks.pessimismOf(tree, launch, lp, cap, capArr);
    }

    private float[][] pairVariants(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap, boolean hold) {
        return clocks.pairVariants(tree, lp, cap, hold);
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
        r.endpoint = v; r.launch = launch; r.holdLaunch = launch; r.fast = fast;
        r.captureClockMax = capArr[iMax]; r.captureClockMin = capArr[iMin];
        r.setupCheck = v.check[iMax]; r.holdCheck = v.check[iMin];
        float[][] both = pessimismOf(getClockTree(cap.getNet()), launch, lp, cap, capArr);
        float[] cpr = both[0], hcpr = both[1];
        r.launchClockMax = lArr[iMax]; r.launchClockMin = lArr[iMin];
        r.setupPessimism = cpr[fast ? 1 : 0]; r.holdPessimism = hcpr[fast ? 1 : 0];
        r.dataMax = graph.pathArrival(path, v, iMax);
        r.dataMin = graph.pathArrival(hpath, v, iMin);
        if (crossesSlr(launch, v)) {
            float ccd = commonDelayMin(getClockTree(cap.getNet()), lp, cap)[fast ? 1 : 0];
            r.setupSlrComp = (r.captureClockMin - ccd) * SLR_PRORATING;
            r.holdSlrComp = (r.dataMin - ccd) * SLR_PRORATING;
        }
        r.setupSlack = periodPs + r.captureClockMin + r.setupPessimism - setupUncertaintyPs - r.setupCheck - r.dataMax - r.setupSlrComp;
        r.holdSlack = r.dataMin - (r.captureClockMax - r.holdPessimism + holdUncertaintyPs + r.holdCheck) - r.holdSlrComp;
        return r;
    }

    /** {@link #describe} for a pair result: the path from that launch rather than the endpoint's worst path. */
    public String describePair(Result r, boolean setup) {
        int iMax = r.fast ? iFastMax : iSlowMax, iMin = r.fast ? iFastMin : iSlowMin;
        List<VersalTimingGraph.Edge> path = graph.getPathFrom(r.launch, r.endpoint, setup ? iMax : iMin);
        StringBuilder sb = new StringBuilder();
        if (setup) sb.append(String.format("setup slack %.0f ps (%s process): launch %s clock %.0f + data %.0f = arrival %.0f; required = %.0f + capture %.0f + cpr %.0f - unc %.0f - setup %.0f - slr %.0f%n",
                r.setupSlack, r.fast ? "fast" : "slow", r.launch, r.launchClockMax, r.dataMax - r.launchClockMax, r.dataMax, periodPs, r.captureClockMin, r.setupPessimism, setupUncertaintyPs, r.setupCheck, r.setupSlrComp));
        else sb.append(String.format("hold slack %.0f ps (%s process): launch clock %.0f + data %.0f = arrival %.0f; required = capture %.0f - cpr %.0f + unc %.0f + hold %.0f + slr %.0f%n",
                r.holdSlack, r.fast ? "fast" : "slow", r.launchClockMin, r.dataMin - r.launchClockMin, r.dataMin, r.captureClockMax, r.holdPessimism, holdUncertaintyPs, r.holdCheck, r.holdSlrComp));
        sb.append(graph.formatPath(path, r.endpoint, setup ? iMax : iMin));
        return sb.toString();
    }

    /** Every endpoint's results, slow process then fast, in endpoint order (rebuilt after each update). */
    public List<Result> getResults() {
        if (results == null) {
            List<Result> list = new ArrayList<>(byEndpoint.size() * 2);
            for (Result[] rs : byEndpoint.values()) for (Result r : rs) if (r != null) list.add(r);
            results = list;
        }
        return results;
    }

    /** The {slow, fast} results of one endpoint (entries null when that process has none), or null. */
    public Result[] getResults(VersalTimingGraph.Vertex endpoint) {
        return byEndpoint.get(endpoint);
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
                periodPs, setupUncertaintyPs, holdUncertaintyPs, getResults().size() / 2, unclockedLaunches, unclockedEndpoints));
        for (Map.Entry<Net, VersalClockModel.ClockTree> e : clocks.getClockTrees().entrySet()) sb.append("clock net ").append(e.getKey().getName()).append(": ").append(e.getValue().sinkArrival.size()).append(" sinks\n");
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
            sb.append(String.format("WNS %.0f ps at %s (%s process): launch %s clock %.0f + data %.0f = arrival %.0f; required = %.0f + capture %.0f + cpr %.0f - unc %.0f - setup %.0f - slr %.0f%n",
                    r.setupSlack, r.endpoint, r.fast ? "fast" : "slow", r.launch, r.launchClockMax, r.dataMax - r.launchClockMax, r.dataMax,
                    periodPs, r.captureClockMin, r.setupPessimism, setupUncertaintyPs, r.setupCheck, r.setupSlrComp));
            sb.append(graph.formatPath(graph.getPath(r.endpoint, iMax, r.setupTag), r.endpoint, iMax));
        } else {
            sb.append(String.format("WHS %.0f ps at %s (%s process): launch clock %.0f + data %.0f = arrival %.0f; required = capture %.0f - cpr %.0f + unc %.0f + hold %.0f + slr %.0f%n",
                    r.holdSlack, r.endpoint, r.fast ? "fast" : "slow", r.launchClockMin, r.dataMin - r.launchClockMin, r.dataMin,
                    r.captureClockMax, r.holdPessimism, holdUncertaintyPs, r.holdCheck, r.holdSlrComp));
            sb.append(graph.formatPath(graph.getPath(r.endpoint, iMin, r.holdTag), r.endpoint, iMin));
        }
        return sb.toString();
    }
}
