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

    /** The launch and the endpoint sit in different SLRs (the data path crosses an SLL). */
    private static boolean crossesSlr(VersalTimingGraph.Vertex launch, VersalTimingGraph.Vertex v) {
        if (launch == null || v == null || launch.cell == null || v.cell == null) return false;
        com.xilinx.rapidwright.device.Site a = launch.cell.getSite(), b = v.cell.getSite();
        if (a == null || b == null) return false;
        com.xilinx.rapidwright.device.SLR sa = a.getTile().getSLR(), sb = b.getTile().getSLR();
        return sa != null && sb != null && sa.getId() != sb.getId();
    }

    /** {slow, fast} min-corner clock delay to the nearest common node of a launch and a capture pin (Vivado's CCD); 0 across clock nets. */
    private final Map<SitePinInst, Map<SitePinInst, float[]>> pairCcd = new HashMap<>();
    private float[] commonDelayMin(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap) {
        if (lp == null || cap == null || lp.getNet() != cap.getNet()) return new float[2];
        return pairCcd.computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap, c -> {
            float[] d = clockModel.commonClockDelay(tree, lp, c);
            return d == null ? new float[2] : new float[] {d[1], d[3]};
        });
    }

    private final Design design;
    private final VersalTimingModel model;
    private final VersalTimingGraph graph;
    private final VersalClockModel clockModel;
    private final float periodPs, setupUncertaintyPs, holdUncertaintyPs;
    private final Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
    private final Map<VersalTimingGraph.Vertex, SitePinInst> clockPinOf = new HashMap<>();
    private static final boolean DEBUG_CLKARC = System.getenv("DEBUG_CLKARC") != null;
    private int debugClkArc = 0;
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

    /** Clock arrival at (site pin, cell), computed once: the same pin is asked for as a launch, as a capture and in every pessimism check against it. */
    private final Map<SitePinInst, Map<Cell, float[]>> arrivalOf = new HashMap<>();
    private static final float[] NO_ARRIVAL = new float[0];

    private float[] clockArrival(SitePinInst spi, Cell cell) {
        if (spi == null) return null;
        float[] a = arrivalOf.computeIfAbsent(spi, k -> new HashMap<>(2)).computeIfAbsent(cell, c -> {
            float[] r = clockModel.pinArrival(getClockTree(spi.getNet()), spi, c);
            if (r == null) return NO_ARRIVAL;
            float[] mux = clockInputArc(spi, c);
            if (mux != null) { r = r.clone(); for (int i = 0; i < r.length && i < mux.length; i++) r[i] += mux[i]; }
            return r;
        });
        return a == NO_ARRIVAL ? null : a;
    }

    /**
     * On a hard block the clock enters through a mux/inverter BEL of its own (DSP58: SRCMXINV, CLK_NAT -> CLK,
     * 59-63 ps; Vivado's path reports show it as a cell arc after the clock net, "Prop_SRCMXINV_DSP58_CLK_IN_CLK")
     * before the internal clock wire reaches the registers. The clock net delay Vivado reports, and so the
     * CLKSITE term fitted from it, end at that BEL's input, so the arc is added here from the logic tables:
     * the cell placed on the BEL the sink site pin connects to, from that pin to its output, when it is not the
     * clocked cell itself. Slices have no such BEL (the site pin's BEL is the flop) and get nothing.
     */
    private float[] clockInputArc(SitePinInst spi, Cell cell) {
        com.xilinx.rapidwright.design.SiteInst si = spi.getSiteInst();
        com.xilinx.rapidwright.device.BELPin bp = spi.getBELPin();
        boolean dbg = DEBUG_CLKARC && si != null && !si.getSiteTypeEnum().name().startsWith("SLICE") && debugClkArc++ < 8;
        if (dbg) System.out.println("clkarc " + spi + " belpin " + bp + " bel " + (bp == null ? null : bp.getBEL()) + " cell " + (bp == null || bp.getBEL() == null ? null : si.getCell(bp.getBEL())) + " for " + cell);
        if (si == null || bp == null || bp.getBEL() == null) return null;
        // the site pin's own BEL pin is the site port; the BELs it feeds are on its site wire
        for (com.xilinx.rapidwright.device.BELPin in : bp.getSiteConns()) {
            if (!in.isInput() || in.getBEL() == null) continue;
            Cell mux = si.getCell(in.getBEL());
            if (mux == null || mux == cell) continue;
            for (com.xilinx.rapidwright.device.BELPin out : in.getBEL().getPins()) {
                if (!out.isOutput()) continue;
                float[] d = graph.cellArc(mux, in.getName(), out.getName());
                if (dbg) System.out.println("clkarc   " + in + " -> " + out.getName() + " = " + java.util.Arrays.toString(d));
                if (d != null) return d;
            }
        }
        return null;
    }

    /**
     * The clock group of a launch: the clock-tree node feeding its clock site pin (the leaf; the site
     * pin's own node when the tree has no parent for it). Launches in one group share their clock path
     * down to that node, so against any capture their pessimism removal differs by at most the spread
     * of the last step; the analysis therefore only needs the extreme arrival of each group.
     */
    private Object launchGroup(SitePinInst spi) {
        Node n = spi.getConnectedNode();
        if (n == null) return spi;
        Node p = getClockTree(spi.getNet()).parent.get(n);
        return p != null ? p : n;
    }

    /**
     * Builds the graph, seeds launches with clock arrivals (one arrival group per launch clock leaf),
     * propagates, and computes every slack: per endpoint and process the worst slack over the groups,
     * each with the pessimism removal of its own launch.
     */
    /** Wall time of the analysis phases after the build: clock seeding, arrival propagation, slack (ms). */
    public final long[] phaseMs = new long[3];

    public void run() {
        graph.build();
        long t = System.currentTimeMillis();
        for (VersalTimingGraph.Vertex q : graph.getLaunches()) {
            SitePinInst spi = clockSitePin(q);
            float[] arr = clockArrival(spi, q.cell);
            if (arr == null) { unclockedLaunches++; graph.unseedLaunch(q); continue; }
            graph.seedLaunch(q, arr, launchGroup(spi));
        }
        phaseMs[0] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        graph.computeArrivals();
        phaseMs[1] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        for (VersalTimingGraph.Vertex v : graph.getEndpoints()) {
            SitePinInst cap = clockSitePin(v);
            float[] capArr = clockArrival(cap, v.cell);
            if (capArr == null) { unclockedEndpoints++; continue; }
            List<VersalTimingGraph.Tagged> tags = VersalTimingGraph.getTags(v);
            if (tags.isEmpty()) continue;
            VersalClockModel.ClockTree tree = getClockTree(cap.getNet());
            Map<VersalTimingGraph.Vertex, float[][]> cprOf = new HashMap<>();   // launch -> {setup {slow, fast}, hold {slow, fast}}
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
                results.add(r);
                if (worstSetup == null || r.setupSlack < worstSetup.setupSlack) worstSetup = r;
                if (worstHold == null || r.holdSlack < worstHold.holdSlack) worstHold = r;
            }
        }
        phaseMs[2] = System.currentTimeMillis() - t;
    }

    /** The launch at the head of an arrival group's path into an endpoint at a corner (the endpoint itself if none). */
    private VersalTimingGraph.Vertex launchOf(VersalTimingGraph.Vertex v, int corner, Object tag) {
        List<VersalTimingGraph.Edge> path = graph.getPath(v, corner, tag);
        return path.isEmpty() ? v : path.get(0).src;
    }

    /** {setup {slow, fast}, hold {slow, fast}} pessimism removal between a launch and a capture pin; zero across clock nets. */
    private float[][] pessimismOf(VersalClockModel.ClockTree tree, VersalTimingGraph.Vertex launch, SitePinInst cap) {
        return pessimismOf(tree, launch, clockSitePin(launch), cap, null);
    }

    /**
     * Pessimism removal for a launch and a capture pin. When both are the same site pin with the same
     * arrival (a DSP58's internal register stages, whose clock enters by one site pin), the whole clock
     * path is common, including the site's own segment and the implicit leaf the tree model adds after
     * the tree, so the credit is the pin's full max - min spread at each process; the tree-based
     * computation stops at the tree node and would leave that segment's spread out (-56 ps setup, -44
     * hold on the 8x8's DSP stages).
     */
    private float[][] pessimismOf(VersalClockModel.ClockTree tree, VersalTimingGraph.Vertex launch, SitePinInst lp, SitePinInst cap, float[] capArr) {
        if (lp == null || lp.getNet() != cap.getNet()) return new float[][] {new float[2], new float[2]};
        if (lp == cap && lp.getSiteInst() != null && !lp.getSiteInst().getSiteTypeEnum().name().startsWith("SLICE")) {
            // hard blocks only: two flops of one slice share its clock site pin too, but Vivado does not credit
            // the slice's own clock segment between them (applying it there moved the 8x8's flop-to-flop hold
            // errors over 25 ps from 51 to 1849)
            float[] lArr = clockArrival(lp, launch.cell);
            if (capArr == null) capArr = clockArrival(cap, launch.cell);
            if (lArr != null && capArr != null && java.util.Arrays.equals(lArr, capArr)) {
                float[] spread = {lArr[iSlowMax] - lArr[iSlowMin], lArr[iFastMax] - lArr[iFastMin]};
                return new float[][] {spread, spread.clone()};
            }
        }
        return pairPessimism(tree, lp, cap);
    }

    /** {setup {slow, fast}, hold {slow, fast}} per (launch pin, capture pin), computed once: the same pair recurs for every endpoint of a site. */
    private final Map<SitePinInst, Map<SitePinInst, float[][]>> pairCpr = new HashMap<>();
    private float[][] pairPessimism(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap) {
        return pairCpr.computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap,
                c -> new float[][] {clockModel.pessimism(tree, lp, c), clockModel.holdPessimism(tree, lp, c)});
    }

    /** The six pessimism variants ({@link VersalClockModel#pessimism(VersalClockModel.ClockTree, SitePinInst, SitePinInst, int, boolean)}) per (launch pin, capture pin), [variant][slow, fast]. */
    private final Map<SitePinInst, Map<SitePinInst, float[][]>> pairVariantsSetup = new HashMap<>(), pairVariantsHold = new HashMap<>();
    private float[][] pairVariants(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap, boolean hold) {
        return (hold ? pairVariantsHold : pairVariantsSetup).computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap, c -> {
            float[][] out = new float[6][];
            for (int vv = 0; vv < 6; vv++) out[vv] = clockModel.pessimism(tree, lp, c, vv, hold);
            return out;
        });
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
