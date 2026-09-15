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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;

/**
 * The clock side of a Versal slack computation, shared by the report ({@link VersalSlackAnalysis})
 * and the timing-driven router ({@link VersalRWTimingGraph}): the clock tree of every global clock
 * net ({@link VersalClockModel#analyze}), the clock site pin of a launch or endpoint vertex, the
 * clock arrival at that pin for its cell (the tree's arrival plus a hard block's clock input arc),
 * the pessimism removal and the common clock delay of a launch/capture pair, and the launch clock
 * group. Everything is cached: the same pin is asked for as a launch, as a capture and in every
 * pessimism check against it.
 *
 * <p>Clock arrays are the clock model's, always four long in the fixed order {slow max, slow min,
 * fast max, fast min}; the data model may have fewer corners (the router's has slow max and slow
 * min), and its corners are assumed to be a prefix of that order where the two are added.
 */
public class VersalClockArrivals {

    /** index of the slow max / slow min / fast max / fast min corner in the clock model's arrays */
    public static final int SLOW_MAX = 0, SLOW_MIN = 1, FAST_MAX = 2, FAST_MIN = 3;

    private final VersalTimingGraph graph;
    private final VersalTimingModel model;
    private final VersalClockModel clockModel;
    private final Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
    private final Map<VersalTimingGraph.Vertex, SitePinInst> clockPinOf = new HashMap<>();
    /** clock arrival at (site pin, cell), computed once */
    private final Map<SitePinInst, Map<Cell, float[]>> arrivalOf = new HashMap<>();
    private static final float[] NO_ARRIVAL = new float[0];
    /** {slow, fast} min-corner clock delay to the nearest common node per (launch pin, capture pin) */
    private final Map<SitePinInst, Map<SitePinInst, float[]>> pairCcd = new HashMap<>();
    /** {setup {slow, fast}, hold {slow, fast}} per (launch pin, capture pin) */
    private final Map<SitePinInst, Map<SitePinInst, float[][]>> pairCpr = new HashMap<>();
    private final Map<SitePinInst, Map<SitePinInst, float[][]>> pairVariantsSetup = new HashMap<>(), pairVariantsHold = new HashMap<>();
    private static final boolean DEBUG_CLKARC = System.getenv("DEBUG_CLKARC") != null;
    private int debugClkArc = 0;

    public VersalClockArrivals(VersalTimingGraph graph, VersalTimingModel model, VersalClockModel clockModel) {
        this.graph = graph;
        this.model = model;
        this.clockModel = clockModel;
    }

    public VersalClockModel getClockModel() {
        return clockModel;
    }

    /** The clock trees analysed so far, by net. */
    public Map<Net, VersalClockModel.ClockTree> getClockTrees() {
        return trees;
    }

    public VersalClockModel.ClockTree getClockTree(Net net) {
        return trees.computeIfAbsent(net, n -> clockModel.analyze(n, model));
    }

    /** The clock net sink pin feeding a vertex's cell clock, or null (no clock pin, or its net is not a routed global clock). */
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

    /**
     * Clock arrival (four clock corners) at a site pin for the cell it clocks: the tree's arrival at the
     * pin plus the cell's clock input arc on a hard block; null when the pin is null or not in a tree.
     */
    public float[] clockArrival(SitePinInst spi, Cell cell) {
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
        SiteInst si = spi.getSiteInst();
        BELPin bp = spi.getBELPin();
        boolean dbg = DEBUG_CLKARC && si != null && !si.getSiteTypeEnum().name().startsWith("SLICE") && debugClkArc++ < 8;
        if (dbg) System.out.println("clkarc " + spi + " belpin " + bp + " bel " + (bp == null ? null : bp.getBEL()) + " cell " + (bp == null || bp.getBEL() == null ? null : si.getCell(bp.getBEL())) + " for " + cell);
        if (si == null || bp == null || bp.getBEL() == null) return null;
        // the site pin's own BEL pin is the site port; the BELs it feeds are on its site wire
        for (BELPin in : bp.getSiteConns()) {
            if (!in.isInput() || in.getBEL() == null) continue;
            Cell mux = si.getCell(in.getBEL());
            if (mux == null || mux == cell) continue;
            for (BELPin out : in.getBEL().getPins()) {
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
     * of the last step.
     */
    public Object launchGroup(SitePinInst spi) {
        Node n = spi.getConnectedNode();
        if (n == null) return spi;
        Node p = getClockTree(spi.getNet()).parent.get(n);
        return p != null ? p : n;
    }

    /** The launch and the endpoint sit in different SLRs (the data path crosses an SLL). */
    public static boolean crossesSlr(VersalTimingGraph.Vertex launch, VersalTimingGraph.Vertex v) {
        if (launch == null || v == null || launch.cell == null || v.cell == null) return false;
        Site a = launch.cell.getSite(), b = v.cell.getSite();
        if (a == null || b == null) return false;
        com.xilinx.rapidwright.device.SLR sa = a.getTile().getSLR(), sb = b.getTile().getSLR();
        return sa != null && sb != null && sa.getId() != sb.getId();
    }

    /** {slow, fast} min-corner clock delay to the nearest common node of a launch and a capture pin (Vivado's CCD); 0 across clock nets. */
    public float[] commonDelayMin(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap) {
        if (lp == null || cap == null || lp.getNet() != cap.getNet()) return new float[2];
        return pairCcd.computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap, c -> {
            float[] d = clockModel.commonClockDelay(tree, lp, c);
            return d == null ? new float[2] : new float[] {d[1], d[3]};
        });
    }

    /**
     * Pessimism removal {setup {slow, fast}, hold {slow, fast}} for a launch and a capture pin; zero
     * across clock nets. When both are the same site pin with the same arrival (a DSP58's internal
     * register stages, whose clock enters by one site pin), the whole clock path is common, including
     * the site's own segment and the implicit leaf the tree model adds after the tree, so the credit
     * is the pin's full max - min spread at each process; the tree-based computation stops at the tree
     * node and would leave that segment's spread out (-56 ps setup, -44 hold on the 8x8's DSP stages).
     * Two flops of one slice share its clock site pin too, but Vivado does not credit the slice's own
     * clock segment between them (applying it there moved the 8x8's flop-to-flop hold errors over
     * 25 ps from 51 to 1849), so the rule is for hard blocks only.
     * @param capArr the capture pin's arrival for the launch's cell, or null to look it up
     */
    public float[][] pessimismOf(VersalClockModel.ClockTree tree, VersalTimingGraph.Vertex launch, SitePinInst lp, SitePinInst cap, float[] capArr) {
        if (lp == null || cap == null || lp.getNet() != cap.getNet()) return new float[][] {new float[2], new float[2]};
        if (lp == cap && lp.getSiteInst() != null && !lp.getSiteInst().getSiteTypeEnum().name().startsWith("SLICE")) {
            float[] lArr = clockArrival(lp, launch.cell);
            if (capArr == null) capArr = clockArrival(cap, launch.cell);
            if (lArr != null && capArr != null && java.util.Arrays.equals(lArr, capArr)) {
                float[] spread = {lArr[SLOW_MAX] - lArr[SLOW_MIN], lArr[FAST_MAX] - lArr[FAST_MIN]};
                return new float[][] {spread, spread.clone()};
            }
        }
        return pairPessimism(tree, lp, cap);
    }

    /** {setup {slow, fast}, hold {slow, fast}} per (launch pin, capture pin), computed once: the same pair recurs for every endpoint of a site. */
    public float[][] pairPessimism(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap) {
        return pairCpr.computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap,
                c -> new float[][] {clockModel.pessimism(tree, lp, c), clockModel.holdPessimism(tree, lp, c)});
    }

    /** The six pessimism variants ({@link VersalClockModel#pessimism(VersalClockModel.ClockTree, SitePinInst, SitePinInst, int, boolean)}) per (launch pin, capture pin), [variant][slow, fast]. */
    public float[][] pairVariants(VersalClockModel.ClockTree tree, SitePinInst lp, SitePinInst cap, boolean hold) {
        return (hold ? pairVariantsHold : pairVariantsSetup).computeIfAbsent(lp, k -> new HashMap<>()).computeIfAbsent(cap, c -> {
            float[][] out = new float[6][];
            for (int vv = 0; vv < 6; vv++) out[vv] = clockModel.pessimism(tree, lp, c, vv, hold);
            return out;
        });
    }

    /** Forgets the cached arrivals of the pins on the given sites (their leaf clock delays changed). */
    public void invalidateSites(Set<Site> sites) {
        arrivalOf.keySet().removeIf(spi -> spi.getSite() != null && sites.contains(spi.getSite()));
    }
}
