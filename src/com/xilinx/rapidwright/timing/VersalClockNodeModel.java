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
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.util.FileTools;

/**
 * The per-node clock model: arrival and pessimism computed from whatever
 * clock route exists, with no per-design tables.
 *
 * <p>Arrival at a sink is the sum along its route of per-<em>instance</em>
 * terms for the trunk (spine and distribution nodes — each a fixed piece of
 * silicon, so a device constant) and per-<em>type</em> terms for the leaf
 * level, plus the buffer constant. Pessimism removal for a pair is the
 * max-minus-min corner spread of the route prefix the two share, priced by
 * type-by-tile-type terms — the featurization the pessimism check validated
 * at 17 ps with no divergence-node table at all.
 *
 * <p>A trunk instance the table has never seen falls back to its
 * type-by-tile term and is counted; full device coverage comes from the
 * clock trunk sweep, after which no per-design measurement remains.
 *
 * <p>Table sections ({@code clock_node_delay} — arrival terms,
 * {@code clock_pessimism_delay} — type-by-tile terms), columns
 * {@code key slow_max_ps slow_min_ps}, produced by the harness's
 * {@code ClkNodeFit}.
 */
public class VersalClockNodeModel implements ClockDelayModel {

    /** Default table location, relative to the RapidWright installation. */
    public static final String DEFAULT_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/clock_node_delay.txt";

    public static final String BRANCH_TERM = "BRANCHES_PASSED";

    private final Map<String, double[]> arrival = new HashMap<>();
    private final Map<String, double[]> byType = new HashMap<>();
    /** Arrival-scale fallback per wire template, from fitted instance means. */
    private final Map<String, double[]> typeFallback = new HashMap<>();
    private final int bufgMaxPs;
    private final int bufgMinPs;

    /**
     * Table header that switches on Vivado's balanced-tree semantics: the
     * fit was made with the vertical distribution of every row padded to the
     * slowest row (the SSIT delay stations' optimized delay), so a sink's
     * arrival is the tree's target plus its route below the row anchor.
     */
    public static final String DESKEW_HEADER = "clock_deskew";
    private boolean deskew;
    /**
     * Grid form: the balanced vertical trunk is a measured term per spine
     * column and top row ({@code TARGET:<tileX>:<tileY>}), shared by every
     * tree with that geometry, and a sink's arrival is its origin-to-spine
     * hops + that term + its route below the row anchor.
     */
    public static final String TARGET_PREFIX = "TARGET:";
    /**
     * Table section of the common clock delay: the physical (undeskewed)
     * arrival per trunk instance, fitted to Vivado's inter-SLR compensation
     * (CcdFit), with a {@code BUFG} constant. A balanced tree's arrival
     * terms cannot give the arrival partway up the vertical trunk.
     */
    public static final String COMMON_SECTION = "clock_common_delay";
    private final Map<String, double[]> common = new HashMap<>();
    private boolean targetGrid;
    /** Extent form: terms keyed by spine column, bottom and top loaded rows ({@code TARGET:<x>:<ya>:<yb>}). */
    private boolean targetExtent;
    /** Spine column tile X -> top row tile Y -> target term. */
    private final Map<Integer, java.util.TreeMap<Integer, double[]>> targets = new HashMap<>();
    /** Extent form: spine X -> bottom row Y -> top row Y -> target term. */
    private final Map<Integer, java.util.TreeMap<Integer, java.util.TreeMap<Integer, double[]>>> extentTargets = new HashMap<>();
    private int treeBottomY = Integer.MAX_VALUE;
    /** Per route: index of the first vertical trunk node, or -1. */
    private final Map<List<Node>, Integer> spineIndex = new java.util.IdentityHashMap<>();
    private int treeTopY = -1;
    /** Per route: index of the first node after the last vertical trunk node, or -1. */
    private final Map<List<Node>, Integer> anchorIndex = new java.util.IdentityHashMap<>();
    /** The tree's balanced arrival at the row anchors: the slowest row's undelayed trunk. */
    private final double[] target = new double[2];
    private List<Node> targetRoute;

    private final Map<Site, List<Node>> siteRoute = new HashMap<>();
    /** Route per clock site pin, keyed "site/pin": a block RAM's two clocks differ. */
    private final Map<String, List<Node>> pinRoute = new HashMap<>();
    private final Map<Node, Integer> children = new HashMap<>();

    private long termsPriced;
    private long termsFallback;
    private long termsUnknown;
    private final boolean armed;
    private final com.xilinx.rapidwright.design.Design design;

    public VersalClockNodeModel(Net clk, Path table) throws IOException {
        this(clk, table, VersalClockTimingModel.DEFAULT_BUFG_MAX_PS,
                VersalClockTimingModel.DEFAULT_BUFG_MIN_PS);
    }

    public VersalClockNodeModel(Net clk, Path table, int bufgMaxPs, int bufgMinPs)
            throws IOException {
        this.bufgMaxPs = bufgMaxPs;
        this.bufgMinPs = bufgMinPs;
        com.xilinx.rapidwright.design.Design design = clk.getDesign();
        this.armed = design != null && hasArmedStations(design);
        this.design = design;
        read(table);
        if (arrival.isEmpty()) {
            throw new IOException("No clock_node_delay entries in " + table);
        }
        Map<Node, Node> parent = VersalClockTimingModel.buildParents(clk);
        for (Map.Entry<Node, Node> e : parent.entrySet()) {
            if (e.getValue() != null) {
                children.merge(e.getValue(), 1, Integer::sum);
            }
        }
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) {
                continue;
            }
            List<Node> route = VersalClockTimingModel.routeToSink(parent, spi.getConnectedNode());
            if (!route.isEmpty()) {
                siteRoute.putIfAbsent(spi.getSite(), route);
                pinRoute.put(spi.getSite().getName() + "/" + spi.getName(), route);
            }
        }
        // A DSP58 is a macro whose clock is an internal net, and Vivado's
        // checkpoint gives the physical clock no site pin there. Any site
        // whose cells are clocked by this net through such a boundary gets
        // its route from the site's own CLK pin node.
        if (design != null) {
            for (com.xilinx.rapidwright.design.SiteInst si : design.getSiteInsts()) {
                Site site = si.getSite();
                if (siteRoute.containsKey(site)) {
                    continue;
                }
                int pin = site.getPinIndex("CLK");
                if (pin < 0 || !clockedBy(si, clk)) {
                    continue;
                }
                List<Node> route = VersalClockTimingModel.routeToSink(parent, site.getConnectedNode(pin));
                if (!route.isEmpty()) {
                    siteRoute.put(site, route);
                    pinRoute.put(site.getName() + "/CLK", route);
                    sitesFromSitePin++;
                }
            }
        }
        computeTarget();
    }

    private int sitesFromSitePin;

    /**
     * Balanced-tree target: every row's vertical distribution is padded to
     * the slowest row's arrival, so the target is the maximum over all
     * routes of the undelayed trunk sum to the row anchor. Only trees that
     * cross armed stations are balanced.
     */
    private void computeTarget() {
        if (!deskew || !armed) {
            return;
        }
        if (targetGrid) {
            for (List<Node> route : pinRoute.values()) {
                int a = anchorOf(route);
                anchorIndex.put(route, a);
                spineIndex.put(route, spineOf(route));
                if (a > 0) {
                    int y = route.get(a - 1).getTile().getTileYCoordinate();
                    treeTopY = Math.max(treeTopY, y);
                    treeBottomY = Math.min(treeBottomY, y);
                }
            }
            return;
        }
        // The slowest row is the topmost one (the buffers sit in the bottom
        // HSR). Its undelayed trunk is the target; a trunk priced through a
        // fallback term is only used when no exactly priced one exists.
        int bestY = Integer.MIN_VALUE;
        boolean bestExact = false;
        for (List<Node> route : pinRoute.values()) {
            int a = anchorOf(route);
            anchorIndex.put(route, a);
            if (a < 0) {
                continue;
            }
            double[] up = new double[2];
            boolean exact = true;
            for (int ci = 0; ci < 2; ci++) {
                up[ci] = ci == 1 ? bufgMinPs : bufgMaxPs;
                for (int i = 0; i < a; i++) {
                    up[ci] += nodeTermPs(route.get(i), ci == 1 ? Corner.SLOW_MIN : Corner.SLOW_MAX);
                    exact &= hasArrivalTerm(route.get(i));
                }
            }
            int y = route.get(a - 1).getTile().getTileYCoordinate();
            boolean better;
            if (targetRoute == null) {
                better = true;
            } else if (exact != bestExact) {
                better = exact;
            } else if (y != bestY) {
                better = y > bestY;
            } else {
                better = up[0] > target[0];
            }
            if (better) {
                target[0] = up[0];
                target[1] = up[1];
                targetRoute = route;
                bestY = y;
                bestExact = exact;
            }
        }
    }

    /** Index of the first vertical trunk node (the spine column) of a route, or -1. */
    public static int spineOf(List<Node> route) {
        for (int i = 0; i < route.size(); i++) {
            if (route.get(i).getIntentCode().toString().contains("VROUTE")) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Grid target for a spine column and top row: the measured term, or a
     * linear interpolation between the measured heights of that column.
     */
    private double[] gridTarget(int spineX, int topY) {
        java.util.TreeMap<Integer, double[]> col = targets.get(spineX);
        if (targetExtent) {
            // Exact extent, else the nearest measured bottom row of this
            // spine at the same top row (the top row's SLR is the larger
            // step), else the height interpolation of that bottom row.
            java.util.TreeMap<Integer, java.util.TreeMap<Integer, double[]>> byBottom = extentTargets.get(spineX);
            if (byBottom == null || byBottom.isEmpty()) {
                return null;
            }
            double[] exactExtent = byBottom.containsKey(treeBottomY) ? byBottom.get(treeBottomY).get(topY) : null;
            if (exactExtent != null) {
                return exactExtent;
            }
            // The extent is measured at another spine: the spine
            // dependence is close to a constant (X81 - X53 = -150 +- 60 ps
            // over every Y1_Yb), so use that spine's term plus the mean
            // offset between the two spines over their shared extents.
            for (Map.Entry<Integer, java.util.TreeMap<Integer, java.util.TreeMap<Integer, double[]>>> o
                    : extentTargets.entrySet()) {
                if (o.getKey() == spineX) {
                    continue;
                }
                java.util.TreeMap<Integer, double[]> top = o.getValue().get(treeBottomY);
                double[] other = top == null ? null : top.get(topY);
                if (other == null) {
                    continue;
                }
                double[] off = new double[2];
                int n = 0;
                for (Map.Entry<Integer, java.util.TreeMap<Integer, double[]>> b : byBottom.entrySet()) {
                    java.util.TreeMap<Integer, double[]> ob = o.getValue().get(b.getKey());
                    if (ob == null) {
                        continue;
                    }
                    for (Map.Entry<Integer, double[]> t : b.getValue().entrySet()) {
                        double[] ov = ob.get(t.getKey());
                        if (ov != null) {
                            off[0] += t.getValue()[0] - ov[0];
                            off[1] += t.getValue()[1] - ov[1];
                            n++;
                        }
                    }
                }
                if (n > 0) {
                    return new double[] { other[0] + off[0] / n, other[1] + off[1] / n };
                }
            }
            Map.Entry<Integer, java.util.TreeMap<Integer, double[]>> lo = byBottom.floorEntry(treeBottomY);
            Map.Entry<Integer, java.util.TreeMap<Integer, double[]>> hi = byBottom.ceilingEntry(treeBottomY);
            Map.Entry<Integer, java.util.TreeMap<Integer, double[]>> pick = lo == null ? hi
                    : hi == null ? lo
                    : (treeBottomY - lo.getKey() <= hi.getKey() - treeBottomY ? lo : hi);
            col = pick.getValue();
        }
        if (col == null || col.isEmpty()) {
            return null;
        }
        double[] exact = col.get(topY);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, double[]> lo = col.floorEntry(topY);
        Map.Entry<Integer, double[]> hi = col.ceilingEntry(topY);
        if (lo == null) {
            return hi.getValue();
        }
        if (hi == null) {
            return lo.getValue();
        }
        double w = (topY - lo.getKey()) / (double) (hi.getKey() - lo.getKey());
        return new double[] { lo.getValue()[0] + w * (hi.getValue()[0] - lo.getValue()[0]),
                lo.getValue()[1] + w * (hi.getValue()[1] - lo.getValue()[1]) };
    }

    /** Index after the last vertical trunk node of a route, or -1 if none or nothing follows. */
    public static int anchorOf(List<Node> route) {
        int a = -1;
        for (int i = 0; i < route.size(); i++) {
            String ic = route.get(i).getIntentCode().toString();
            if (ic.contains("VDISTR") || ic.contains("VROUTE")) {
                a = i + 1;
            }
        }
        return a >= route.size() ? -1 : a;
    }

    /** The balanced-tree target arrival at the row anchors, or null when the tree is not balanced. */
    public Double getTargetPs(Corner corner) {
        return deskew && armed && targetRoute != null ? target[corner == Corner.SLOW_MIN ? 1 : 0] : null;
    }

    /** Grid form: the tree's top anchor row, or -1. */
    public int getTreeTopY() {
        return treeTopY;
    }

    /** Grid form: the measured target for a spine column at the tree's top row, or null. */
    public Double getGridTargetPs(int spineX, Corner corner) {
        double[] tg = targetGrid ? gridTarget(spineX, treeTopY) : null;
        return tg == null ? null : tg[corner == Corner.SLOW_MIN ? 1 : 0];
    }

    /** The route whose undelayed trunk sets the target, for diagnostics. */
    public List<Node> getTargetRoute() {
        return targetRoute;
    }

    /** Whether the table carries balanced-tree semantics. */
    public boolean isDeskew() {
        return deskew;
    }

    /** Whether any cell in the site has its CLK driven, through any hierarchy, by this net. */
    private static boolean clockedBy(com.xilinx.rapidwright.design.SiteInst si, Net clk) {
        com.xilinx.rapidwright.edif.EDIFNetlist netlist = si.getDesign().getNetlist();
        for (com.xilinx.rapidwright.design.Cell c : si.getCells()) {
            // The macro's internal clock net does not resolve upward, so
            // climb to the first enclosing instance whose clock port does.
            com.xilinx.rapidwright.edif.EDIFHierCellInst inst = c.getEDIFHierCellInst();
            for (int level = 0; inst != null && level < 4; level++, inst = inst.getParent()) {
                for (com.xilinx.rapidwright.edif.EDIFHierPortInst hp : inst.getHierPortInsts()) {
                    if (!hp.getPortInst().getName().toLowerCase().contains("clk")
                            || hp.getHierarchicalNet() == null) {
                        continue;
                    }
                    com.xilinx.rapidwright.edif.EDIFHierNet pn = netlist.getParentNet(hp.getHierarchicalNet());
                    String name = (pn != null ? pn : hp.getHierarchicalNet()).getHierarchicalNetName();
                    if (name.equals(clk.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Builds the model for a clock net from the default table location. */
    public static VersalClockNodeModel load(Net clk) throws IOException {
        String file = FileTools.getRapidWrightResourceFileName(DEFAULT_FILE);
        if (file == null) {
            throw new IOException("Cannot locate " + DEFAULT_FILE + ": RAPIDWRIGHT_PATH is not set");
        }
        return new VersalClockNodeModel(clk, Paths.get(file));
    }

    // ------------------------------------------------------------------
    // Featurization: the single implementation, delegated to by the
    // characterization harness so fit and prediction cannot drift.
    // ------------------------------------------------------------------

    /** Spine and distribution nodes: the shared trunk above the leaves. */
    public static boolean isTrunk(Node n) {
        String ic = n.getIntentCode().toString();
        return ic.contains("VROUTE") || ic.contains("VDISTR") || ic.contains("HROUTE")
                || (ic.contains("HDISTR") && !ic.contains("LOCAL")) || ic.contains("GCLK")
                || ic.contains("BUFG");
    }

    /**
     * The arrival term key: per instance for trunk nodes, per type for leaves.
     * (Keying leaves by wire template, to separate a block RAM's
     * {@code IRI_QUADRANT_RED} and {@code GREEN} clock pins that Vivado
     * times 700 ps apart, was tried and made Vivado-routed trees worse: the
     * difference is programmed leaf state the fit cannot see, not topology.)
     */
    public static String arrivalKey(Node n) {
        if (!isTrunk(n)) {
            return n.getIntentCode().toString();
        }
        // The clock muxes inside the SSIT rebuffer tiles are physically
        // identical across tiles (per-instance fits scatter around zero), so
        // they are keyed by tile type and wire: a tree through a rebuffer
        // tile no sweep crossed still gets an exact term.
        String wire = n.getWireName();
        if (wire.startsWith("CLK_CMT_MUX")) {
            return n.getTile().getTileTypeEnum() + "/" + wire.replaceAll("\\d+", "#");
        }
        // The number in a trunk wire is its track (HROUTE18, VROUTE18,
        // BUFGCE_52): the 24 tracks are identical wires. Keyed with the
        // track, every tree's trunk is a node set no other tree shares and
        // the fit gains a free per-tree offset that a new tree cannot
        // inherit consistently (fft_3slr held out: -2.1 ns). Keyed by tile
        // and wire template, trees on different tracks share terms and the
        // offsets are pinned. Leaf-site indices place the leaf within the
        // tile and stay.
        if (wire.startsWith("CLK_LEAF_SITES")) {
            return n.toString();
        }
        return n.getTile().getName() + "/" + maskTrack(wire);
    }

    /**
     * Masks the trailing track number of a trunk wire only: in
     * {@code CLK_HROUTE_1_18} the 18 is the track and the 1 the segment
     * (merging segments offset a whole tree by 289 ps).
     */
    public static String maskTrack(String wire) {
        return wire.replaceAll("(\\d+)(?!.*\\d)", "#");
    }

    /** The pessimism (and fallback) term key: type by tile type. */
    public static String typeKey(Node n) {
        return n.getIntentCode() + "@" + n.getTile().getTileTypeEnum();
    }

    /** Feature name for programmed leaf deskew taps at the sink slice. */
    public static final String LEAF_TAPS_TERM = "LEAF_TAPS";
    /** Feature name for armed SSIT delay stations crossed by the route. */
    public static final String ARMED_STATION_TERM = "ARMED_STATIONS";

    /** Programmed leaf deskew taps at a slice, or 0 when the design carries no attributes. */
    public static int leafTaps(com.xilinx.rapidwright.design.Design design, Site site) {
        if (design == null || design.getBELAttrs() == null || !site.getName().startsWith("SLICE")) {
            return 0;
        }
        return com.xilinx.rapidwright.router.VersalClockDeskew.getLeafClockDelay(design, site);
    }

    /** Feature name for programmed delay taps on interface (IRI) leaves. */
    public static final String IRI_TAPS_TERM = "IRI_TAPS";

    /**
     * Programmed delay taps on the interface leaf a route ends through.
     * Block RAM, DSP and other non-slice sinks take their clock from an
     * {@code IRI_QUAD} site whose {@code IRI_FF_CLK_MOD} can select a
     * delayed clock ({@code CLK_SEL=DLYD_CLK}) with {@code CLK_DLY_VAL_COE}
     * taps — Vivado's deskew for those pins, which made a block RAM's two
     * clocks arrive 700 ps apart. Zero when nothing is programmed.
     */
    public static int iriTaps(com.xilinx.rapidwright.design.Design design, List<Node> route) {
        if (design == null || design.getBELAttrs() == null) {
            return 0;
        }
        for (Node n : route) {
            com.xilinx.rapidwright.device.SitePin sp = n.getSitePin();
            if (sp == null || !sp.getSite().getSiteTypeEnum().name().startsWith("IRI_QUAD")) {
                continue;
            }
            com.xilinx.rapidwright.design.SiteConfig sc = design.getBELAttrs().get(sp.getSite());
            if (sc == null) {
                continue;
            }
            for (Map.Entry<com.xilinx.rapidwright.device.BEL, Map<String, com.xilinx.rapidwright.design.BELAttr>> e
                    : sc.getBELAttributes().entrySet()) {
                if (!e.getKey().getName().equals("IRI_FF_CLK_MOD")) {
                    continue;
                }
                com.xilinx.rapidwright.design.BELAttr sel = e.getValue().get("CLK_SEL");
                com.xilinx.rapidwright.design.BELAttr val = e.getValue().get("CLK_DLY_VAL_COE");
                if (sel == null || val == null || !sel.getValue().contains("DLYD")) {
                    return 0;
                }
                return parseTaps(val.getValue());
            }
        }
        return 0;
    }

    /** Parses a Verilog-style sized literal such as {@code 4'hB}, or a plain integer. */
    static int parseTaps(String v) {
        try {
            int q = v.indexOf('\'');
            if (q < 0) {
                return Integer.parseInt(v.trim());
            }
            char base = Character.toLowerCase(v.charAt(q + 1));
            String digits = v.substring(q + 2).trim();
            int radix = base == 'h' ? 16 : base == 'b' ? 2 : base == 'o' ? 8 : 10;
            return Integer.parseInt(digits, radix);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Whether a route node passes through an SSIT programmable delay station. */
    public static boolean isDelayStationNode(Node n) {
        return n.getWireName().contains("PD_OPT_DELAY");
    }

    /**
     * Whether the design armed the SSIT stations' optimized (self-computed)
     * delay. Vivado's router arms them design-wide; an armed station carries
     * delay no route topology reveals, so it is a fitted per-crossing term.
     */
    public static boolean hasArmedStations(com.xilinx.rapidwright.design.Design design) {
        if (design == null || design.getBELAttrs() == null) {
            return false;
        }
        for (Map.Entry<com.xilinx.rapidwright.device.Site, com.xilinx.rapidwright.design.SiteConfig> e
                : design.getBELAttrs().entrySet()) {
            if (e.getKey().getSiteTypeEnum().name().contains("GCLK_DELAY")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // ClockDelayModel.
    // ------------------------------------------------------------------

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.SIGNOFF;
    }

    @Override
    public Float getArrivalPs(Site site, Corner corner) {
        return arrival(site, siteRoute.get(site), corner);
    }

    @Override
    public Float getArrivalPs(Site site, String sitePin, Corner corner) {
        List<Node> route = sitePin == null ? null : pinRoute.get(site.getName() + "/" + sitePin);
        return arrival(site, route != null ? route : siteRoute.get(site), corner);
    }

    /** A node's arrival term at a corner index, exact or by wire-template fallback (0 if unknown). */
    private double priced(Node n, int ci) {
        double[] term = arrival.get(arrivalKey(n));
        if (term != null) {
            termsPriced++;
        } else {
            // A trunk instance the fit never saw: fall back to the mean
            // of fitted instance terms of the same wire template — an
            // arrival-scale value. The pessimism coefficients are
            // whole-route distributors and must never be summed here.
            term = typeFallback.get(n.getWireName().replaceAll("\\d+", "#"));
            if (term != null) {
                termsFallback++;
            } else {
                termsUnknown++;
                return 0;
            }
        }
        return term[ci];
    }

    private Float arrival(Site site, List<Node> route, Corner corner) {
        if (route == null) {
            return null;
        }
        int ci = corner == Corner.SLOW_MIN ? 1 : 0;
        double t = corner == Corner.SLOW_MIN ? bufgMinPs : bufgMaxPs;
        int from = 0;
        if (deskew && armed && targetGrid) {
            Integer anchor = anchorIndex.get(route);
            Integer spine = spineIndex.get(route);
            double[] tg = anchor != null && anchor >= 0 && spine != null && spine >= 0
                    ? gridTarget(route.get(spine).getTile().getTileXCoordinate(), treeTopY) : null;
            if (tg != null) {
                // Origin-to-spine hops, then the measured vertical target,
                // then the route below the row anchor.
                for (int ri = 0; ri < spine; ri++) {
                    t += priced(route.get(ri), ci);
                }
                t += tg[ci];
                from = anchor;
            }
        }
        Integer anchor = deskew && armed && !targetGrid && targetRoute != null ? anchorIndex.get(route) : null;
        if (anchor != null && anchor >= 0) {
            // Balanced tree: the row anchor arrives at the target; only the
            // route below it is priced, stations included as ordinary nodes.
            t = target[ci];
            from = anchor;
        }
        for (int ri = from; ri < route.size(); ri++) {
            t += priced(route.get(ri), ci);
        }
        // Programmed state the checkpoint carries: leaf deskew taps at the
        // sink slice, and armed SSIT delay stations crossed by the route.
        {
            int taps = leafTaps(design, site);
            if (taps > 0) {
                double[] term = byType.get(LEAF_TAPS_TERM);
                t += taps * (term != null ? term[ci] : 68.0);
            }
            int iri = iriTaps(design, route);
            if (iri > 0) {
                double[] term = byType.get(IRI_TAPS_TERM);
                if (term == null) {
                    term = byType.get(LEAF_TAPS_TERM);
                }
                t += iri * (term != null ? term[ci] : 68.0);
            }
        }
        if (armed && !deskew) {
            int stations = 0;
            for (Node n : route) {
                if (isDelayStationNode(n)) {
                    stations++;
                }
            }
            double[] term = byType.get(ARMED_STATION_TERM);
            if (stations > 0 && term != null) {
                t += stations * term[ci];
            }
        }
        return (float) t;
    }

    /**
     * Pessimism removal as the corner spread of the shared route prefix —
     * no divergence-node table. Hold pessimism is reported negative, the way
     * Vivado signs it.
     */
    @Override
    public float getPessimismRemovalPs(Site launch, Site capture, boolean setup) {
        return pessimism(siteRoute.get(launch), siteRoute.get(capture), setup);
    }

    @Override
    public float getPessimismRemovalPs(Site launch, String launchPin, Site capture, String capturePin,
                                       boolean setup) {
        List<Node> a = launchPin == null ? null : pinRoute.get(launch.getName() + "/" + launchPin);
        List<Node> b = capturePin == null ? null : pinRoute.get(capture.getName() + "/" + capturePin);
        return pessimism(a != null ? a : siteRoute.get(launch), b != null ? b : siteRoute.get(capture), setup);
    }

    private float pessimism(List<Node> a, List<Node> b, boolean setup) {
        if (a == null || b == null) {
            return 0;
        }
        int n = Math.min(a.size(), b.size());
        double spread = 0;
        double[] load = byType.get(BRANCH_TERM);
        for (int i = 0; i < n && a.get(i).equals(b.get(i)); i++) {
            Node node = a.get(i);
            double[] term = byType.get(typeKey(node));
            if (term != null) {
                spread += term[0] - term[1];
            }
            int branches = children.getOrDefault(node, 0) - 1;
            if (branches > 0 && load != null) {
                spread += branches * (load[0] - load[1]);
            }
        }
        return (float) (setup ? spread : -spread);
    }

    @Override
    public float getProgrammedDelayPs(Site site, String sitePin, Corner corner) {
        List<Node> route = sitePin == null ? null : pinRoute.get(site.getName() + "/" + sitePin);
        if (route == null) {
            route = siteRoute.get(site);
        }
        return (float) programmedPs(site, route, corner == Corner.SLOW_MIN ? 1 : 0);
    }

    /** Leaf and interface tap delay of a sink, as the arrival adds it. */
    private double programmedPs(Site site, List<Node> route, int ci) {
        double t = 0;
        int taps = leafTaps(design, site);
        if (taps > 0) {
            double[] term = byType.get(LEAF_TAPS_TERM);
            t += taps * (term != null ? term[ci] : 68.0);
        }
        int iri = route == null ? 0 : iriTaps(design, route);
        if (iri > 0) {
            double[] term = byType.get(IRI_TAPS_TERM);
            if (term == null) {
                term = byType.get(LEAF_TAPS_TERM);
            }
            t += iri * (term != null ? term[ci] : 68.0);
        }
        return t;
    }

    @Override
    public Float getCommonClockDelayPs(Site launch, String launchPin, Site capture, String capturePin,
                                       Corner corner) {
        List<Node> a = launchPin == null ? null : pinRoute.get(launch.getName() + "/" + launchPin);
        List<Node> b = capturePin == null ? null : pinRoute.get(capture.getName() + "/" + capturePin);
        if (a == null) {
            a = siteRoute.get(launch);
        }
        if (b == null) {
            b = siteRoute.get(capture);
        }
        if (a == null || b == null) {
            return null;
        }
        int shared = 0;
        int n = Math.min(a.size(), b.size());
        while (shared < n && a.get(shared).equals(b.get(shared))) {
            shared++;
        }
        int ci = corner == Corner.SLOW_MIN ? 1 : 0;
        // The fitted common delay when every shared node is in it.
        double[] bufg = common.get("BUFG");
        if (bufg != null) {
            double t = bufg[ci];
            boolean complete = true;
            for (int i = 0; i < shared && complete; i++) {
                double[] term = common.get(arrivalKey(b.get(i)));
                if (term == null) {
                    complete = false;
                } else {
                    t += term[ci];
                }
            }
            if (complete) {
                return (float) t;
            }
        }
        return (float) prefixArrival(b, shared, ci);
    }

    /** Arrival at the end of the first {@code k} nodes of a route, with the balanced-tree target pro rata. */
    private double prefixArrival(List<Node> route, int k, int ci) {
        double t = ci == 1 ? bufgMinPs : bufgMaxPs;
        if (deskew && armed && targetGrid) {
            Integer anchor = anchorIndex.get(route);
            Integer spine = spineIndex.get(route);
            double[] tg = anchor != null && anchor >= 0 && spine != null && spine >= 0
                    ? gridTarget(route.get(spine).getTile().getTileXCoordinate(), treeTopY) : null;
            if (tg != null) {
                for (int i = 0; i < Math.min(k, spine); i++) {
                    t += priced(route.get(i), ci);
                }
                if (k > spine) {
                    int vertical = Math.max(1, anchor - spine);
                    int sharedVertical = Math.min(k, anchor) - spine;
                    t += tg[ci] * sharedVertical / (double) vertical;
                }
                for (int i = anchor; i < k; i++) {
                    t += priced(route.get(i), ci);
                }
                return t;
            }
        }
        for (int i = 0; i < k; i++) {
            t += priced(route.get(i), ci);
        }
        return t;
    }

    /** Whether the table has an exact arrival term for this route node. */
    public boolean hasArrivalTerm(Node n) {
        return arrival.containsKey(arrivalKey(n));
    }

    /** The arrival term of one route node (exact, template fallback, or 0 if unknown). */
    public double nodeTermPs(Node n, Corner corner) {
        double[] term = arrival.get(arrivalKey(n));
        if (term == null) {
            term = typeFallback.get(n.getWireName().replaceAll("\\d+", "#"));
        }
        return term == null ? 0 : term[corner == Corner.SLOW_MIN ? 1 : 0];
    }

    /** The buffer constant the arrival starts from. */
    public double bufgPs(Corner corner) {
        return corner == Corner.SLOW_MIN ? bufgMinPs : bufgMaxPs;
    }

    /** A fitted pessimism-section term ({@code [max, min]}), or null if absent. */
    public double[] getTerm(String name) {
        double[] t = byType.get(name);
        return t == null ? null : t.clone();
    }

    /** Whether the model has an arrival for this site's clock pin. */
    public boolean covers(Site site) {
        return siteRoute.containsKey(site);
    }

    public int getCoveredSiteCount() {
        return siteRoute.size();
    }

    /** How much pricing used exact terms vs type fallbacks, for reporting. */
    public String getCoverageSummary() {
        long total = termsPriced + termsFallback + termsUnknown;
        return String.format("clock terms: %d exact, %d type-fallback, %d unknown (of %d); %d sites routed from CLK site pin",
                termsPriced, termsFallback, termsUnknown, total, sitesFromSitePin);
    }

    private void read(Path table) throws IOException {
        String section = null;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("clock_node_delay") || t.startsWith("clock_pessimism_delay")
                    || t.startsWith("clock_type_fallback") || t.startsWith(COMMON_SECTION)) {
                section = t.split("\\s+")[0];
                continue;
            }
            if (t.startsWith(DESKEW_HEADER)) {
                deskew = true;
                targetGrid = t.contains("grid") || t.contains("extent");
                targetExtent = t.contains("extent");
                continue;
            }
            String[] f = t.split("\\s+");
            if (section == null || f.length < 3 || f[0].startsWith("TREE:")) {
                continue;
            }
            double[] v = new double[] { Double.parseDouble(f[1]), Double.parseDouble(f[2]) };
            if (section.equals(COMMON_SECTION)) {
                common.put(f[0], v);
                continue;
            }
            if (section.equals("clock_node_delay") && f[0].startsWith(TARGET_PREFIX)) {
                String[] xy = f[0].substring(TARGET_PREFIX.length()).split(":");
                if (xy.length == 3) {
                    extentTargets.computeIfAbsent(Integer.parseInt(xy[0]), k -> new java.util.TreeMap<>())
                            .computeIfAbsent(Integer.parseInt(xy[1]), k -> new java.util.TreeMap<>())
                            .put(Integer.parseInt(xy[2]), v);
                } else {
                    targets.computeIfAbsent(Integer.parseInt(xy[0]), k -> new java.util.TreeMap<>())
                            .put(Integer.parseInt(xy[1]), v);
                }
                continue;
            }
            if (section.equals("clock_node_delay")) {
                arrival.put(f[0], v);
                // Programmed-state features are fitted alongside the arrival
                // terms but consumed as feature coefficients: seen in either
                // section, they must reach the same map or the model silently
                // uses defaults (0 per station, 68 ps per tap).
                if (f[0].equals(ARMED_STATION_TERM) || f[0].equals(LEAF_TAPS_TERM)
                        || f[0].equals(IRI_TAPS_TERM) || f[0].equals(BRANCH_TERM)) {
                    byType.putIfAbsent(f[0], v);
                }
            } else if (section.equals("clock_type_fallback")) {
                typeFallback.put(f[0], v);
            } else if (f[0].equals(ARMED_STATION_TERM) || f[0].equals(LEAF_TAPS_TERM)
                    || f[0].equals(IRI_TAPS_TERM)) {
                // The pessimism variant fits its own copy of these; the
                // arrival variant's is the one arrival must use.
                byType.putIfAbsent(f[0], v);
            } else {
                byType.put(f[0], v);
            }
        }
    }
}
