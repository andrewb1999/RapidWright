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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Versal interconnect delay computed the way the evidence says Vivado
 * computes it: Elmore over the routed tree, from characterized per-wire-class
 * resistance, capacitance and intrinsic delay, plus per-position corrections
 * for horizontal wires.
 *
 * <p>For a sink, the delay is
 * <pre>
 *   Σ_j D(c_j) + span terms  +  Σ_j R(c_j) · Σ_{k ∈ subtree(j)} C(c_k)  +  corrections
 * </pre>
 * over the nodes j on the path from source to sink, where the subtree is
 * taken in the net's whole routed tree — which is how fanout and branching
 * are priced with no explicit fanout terms. Horizontal span wires get an
 * additional correction keyed by (wire class, tile column): rows of the
 * device are uniform silicon, columns are not, and those corrections were
 * measured by a die-wide differential sweep.
 *
 * <p>The featurization here is <em>the</em> implementation: the
 * characterization harness delegates to these statics, so what is fitted and
 * what is predicted cannot drift apart.
 *
 * <p>Tables ({@code rc_delay} and {@code hwire_corr} sections, one file per
 * corner for the RC terms):
 * <pre>
 * rc_delay
 * NODE_HSINGLE:OUT_EE#_E_BEG# 12.3 0.021 3.5   # class D R C
 *
 * hwire_corr
 * NODE_HQUAD:OUT_EE#_E_BEG#@210 14.0 9.5 12    # key corrMax corrMin samples
 * </pre>
 */
public class VersalWireRCModel implements InterconnectDelayModel {

    /** Default table locations, relative to the RapidWright installation. */
    public static final String DEFAULT_MAX_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/rc_delay_slow_max.txt";
    public static final String DEFAULT_MIN_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/rc_delay_slow_min.txt";
    public static final String DEFAULT_CORR_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/hwire_corr.txt";

    /** class → { D, R, C } per corner index (0 = slow_max, 1 = slow_min). */
    private final Map<String, double[]>[] rc;
    /** class@column → { corrMax, corrMin }. */
    private final Map<String, double[]> hwireCorr = new HashMap<>();

    private final Map<Net, Routing> routingCache = new HashMap<>();

    /** How much of what was priced had no table entry, for reporting. */
    private long nodesPriced;
    private long nodesUnknown;
    private long hwireNodes;
    private long hwireCovered;

    @SuppressWarnings("unchecked")
    public VersalWireRCModel(Path maxTable, Path minTable, Path corrTable) throws IOException {
        rc = new Map[] { new HashMap<>(), new HashMap<>() };
        readRC(maxTable, rc[0]);
        readRC(minTable, rc[1]);
        if (corrTable != null && Files.exists(corrTable)) {
            readCorr(corrTable);
        }
        if (rc[0].isEmpty() || rc[1].isEmpty()) {
            throw new IOException("No rc_delay entries in " + maxTable + " / " + minTable);
        }
    }

    /** Loads the tables from their default locations in the RapidWright installation. */
    public static VersalWireRCModel load() throws IOException {
        String max = FileTools.getRapidWrightResourceFileName(DEFAULT_MAX_FILE);
        String min = FileTools.getRapidWrightResourceFileName(DEFAULT_MIN_FILE);
        String corr = FileTools.getRapidWrightResourceFileName(DEFAULT_CORR_FILE);
        if (max == null || min == null) {
            throw new IOException("Cannot locate " + DEFAULT_MAX_FILE + ": RAPIDWRIGHT_PATH is not set");
        }
        return new VersalWireRCModel(Paths.get(max), Paths.get(min),
                corr == null ? null : Paths.get(corr));
    }

    // ------------------------------------------------------------------
    // The featurization. The characterization harness delegates to these,
    // so the fit and this model agree by construction.
    // ------------------------------------------------------------------

    /** A node's wire template: intent code plus its wire name with digit runs collapsed. */
    public static String wireClass(Node n) {
        return n.getIntentCode() + ":" + n.getWireName().replaceAll("\\d+", "#");
    }

    /**
     * Context-aware wire class: the base class plus qualifiers only the
     * route can supply. An SLL crossing is direction-dependent (up-crossings
     * measured ~20 ps slower than down); a span wire driven against its
     * nominal direction is different silicon usage; and an ordinary node
     * inside an SLL tile is not the same wire as its namesake in the fabric.
     * {@code prev} is the node's parent on the route, {@code next} the child
     * the signal continues through (either may be null at the route ends).
     */
    public static String wireClassCtx(Node prev, Node n, Node next) {
        String base = wireClass(n);
        IntentCode ic = n.getIntentCode();
        if (n.getTile().getName().startsWith("SLL") && ic != IntentCode.NODE_SLL_DATA
                && ic != IntentCode.NODE_SLL_INPUT && ic != IntentCode.NODE_SLL_OUTPUT) {
            base += "|SLLT";
        }
        if (ic == IntentCode.NODE_SLL_DATA && prev != null && next != null) {
            base += next.getTile().getTileYCoordinate() >= prev.getTile().getTileYCoordinate()
                    ? "|UP" : "|DN";
        } else if (isSpanWire(n) && prev != null && next != null) {
            String w = n.getWireName();
            int wantY = w.contains("NN") ? 1 : w.contains("SS") ? -1 : 0;
            int wantX = w.contains("EE") ? 1 : w.contains("WW") ? -1 : 0;
            int dy = next.getTile().getTileYCoordinate() - prev.getTile().getTileYCoordinate();
            int dx = next.getTile().getTileXCoordinate() - prev.getTile().getTileXCoordinate();
            if ((wantY != 0 && dy * wantY < 0) || (wantX != 0 && dx * wantX < 0)) {
                base += "|REV";
            }
        }
        return base;
    }

    /** The deterministic representative child of a node in a routed tree. */
    public static Node firstChild(Map<Node, java.util.List<Node>> children, Node n) {
        java.util.List<Node> c = children.get(n);
        if (c == null || c.isEmpty()) {
            return null;
        }
        Node best = c.get(0);
        for (Node x : c) {
            if (x.toString().compareTo(best.toString()) < 0) {
                best = x;
            }
        }
        return best;
    }

    /** Whether a node is a span wire — one that travels — rather than a mux or tap. */
    public static boolean isSpanWire(Node n) {
        IntentCode ic = n.getIntentCode();
        String s = ic.toString();
        return s.contains("SINGLE") || s.contains("DOUBLE") || s.contains("QUAD")
                || s.contains("LONG");
    }

    /** Whether a span wire runs horizontally, which is where position matters. */
    public static boolean isHorizontal(Node n) {
        String w = n.getWireName();
        return w.contains("EE") || w.contains("WW");
    }

    /**
     * The per-position correction key for a span wire: horizontal wires vary
     * by which columns they cross (keyed by column), vertical ones by which
     * rows (keyed by row) — the transverse coordinate is uniform silicon.
     */
    public static String spanKey(Node n) {
        return wireClass(n) + "@"
                + (isHorizontal(n) ? n.getTile().getColumn() : n.getTile().getRow());
    }

    /**
     * Columns a node crosses, counted by the tile type occupying each column
     * (digits collapsed). Rows are uniform; columns are not, and the type is
     * the first-order width variable.
     */
    public static Map<String, Integer> colSpanByType(Node n) {
        Map<Integer, String> colType = new TreeMap<>();
        for (Wire w : n.getAllWiresInNode()) {
            colType.putIfAbsent(w.getTile().getColumn(),
                    w.getTile().getTileTypeEnum().name().replaceAll("\\d+", "#"));
        }
        Map<String, Integer> out = new TreeMap<>();
        if (colType.size() <= 1) {
            return out;
        }
        for (String t : colType.values()) {
            out.merge(t, 1, Integer::sum);
        }
        return out;
    }

    /** Rows a node spans in the tile grid. */
    public static int rowSpan(Node n) {
        int minR = Integer.MAX_VALUE;
        int maxR = Integer.MIN_VALUE;
        for (Wire w : n.getAllWiresInNode()) {
            minR = Math.min(minR, w.getTile().getRow());
            maxR = Math.max(maxR, w.getTile().getRow());
        }
        return minR > maxR ? 0 : maxR - minR;
    }

    // ------------------------------------------------------------------
    // Prediction.
    // ------------------------------------------------------------------

    /** A net's routed tree: parents, and each node's subtree capacitance per corner. */
    private class Routing {
        final Map<Node, Node> parent;
        final Map<Node, double[]> subtreeC = new HashMap<>();

        final Map<Node, List<Node>> children = new HashMap<>();

        Routing(Net net) {
            parent = buildParents(net);
            // Children, then post-order subtree capacitance at both corners.
            List<Node> roots = new ArrayList<>();
            for (Map.Entry<Node, Node> e : parent.entrySet()) {
                if (e.getValue() == null) {
                    roots.add(e.getKey());
                } else {
                    children.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
                }
            }
            if (roots.isEmpty()) {
                return;
            }
            Deque<Node> stack = new ArrayDeque<>();
            Deque<Node> order = new ArrayDeque<>();
            roots.forEach(stack::push);
            while (!stack.isEmpty()) {
                Node n = stack.pop();
                order.push(n);
                for (Node ch : children.getOrDefault(n, Collections.emptyList())) {
                    stack.push(ch);
                }
            }
            while (!order.isEmpty()) {
                Node n = order.pop();
                String cls = wireClassCtx(parent.get(n), n, firstChild(children, n));
                double[] p0 = rcCtx(0, cls, n);
                double[] p1 = rcCtx(1, cls, n);
                double[] c = new double[] { p0 == null ? 0 : p0[2], p1 == null ? 0 : p1[2] };
                for (Node ch : children.getOrDefault(n, Collections.emptyList())) {
                    double[] cc = subtreeC.get(ch);
                    c[0] += cc[0];
                    c[1] += cc[1];
                }
                subtreeC.put(n, c);
                // An SLL crossing is actively buffered (LAG TX -> UBUMP ->
                // LAG RX): capacitance beyond the transmitter does not load
                // the upstream chain. Without the barrier a cross-SLR BRAM
                // net priced +223 ps (slr_ring's worst path).
                if (n.getIntentCode() == com.xilinx.rapidwright.device.IntentCode.NODE_SLL_DATA) {
                    subtreeC.put(n, new double[] { param(0, cls, 2), param(1, cls, 2) });
                }
            }
        }
    }

    private double param(int corner, String cls, int which) {
        double[] v = rc[corner].get(cls);
        return v == null ? 0 : v[which];
    }

    /** Class parameters for a context class, falling back to the base class. */
    private double[] rcCtx(int corner, String ctxCls, Node n) {
        double[] v = rc[corner].get(ctxCls);
        return v != null ? v : rc[corner].get(wireClass(n));
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.SIGNOFF;
    }

    /**
     * Elmore delay from the net's source to the sink at one corner, plus the
     * horizontal per-position corrections along the way. Unknown classes
     * contribute nothing and are counted; see {@link #getCoverageSummary}.
     */
    @Override
    public Float getNetDelayPs(Net net, SitePinInst sink, Corner corner) {
        if (net.getSource() == null || sink == null) {
            return null;
        }
        Routing routing = routingCache.computeIfAbsent(net, Routing::new);
        Node end = sink.getConnectedNode();
        if (end == null || !routing.parent.containsKey(end)) {
            return null;
        }
        int ci = corner == Corner.SLOW_MIN ? 1 : 0;
        double t = 0;
        List<Node> path = new ArrayList<>();
        for (Node n = end; n != null; n = routing.parent.get(n)) {
            path.add(n);
        }
        Collections.reverse(path);
        for (int pi = 0; pi < path.size(); pi++) {
            Node n = path.get(pi);
            Node prevN = pi > 0 ? path.get(pi - 1) : null;
            Node nextN = pi + 1 < path.size() ? path.get(pi + 1)
                    : firstChild(routing.children, n);
            String cls = wireClassCtx(prevN, n, nextN);
            double[] drc = rcCtx(ci, cls, n);
            if (drc == null) {
                nodesUnknown++;
            } else {
                nodesPriced++;
                t += drc[0] + drc[1] * routing.subtreeC.get(n)[ci];
            }
            String base = wireClass(n);
            {
            for (Map.Entry<String, Integer> e : colSpanByType(n).entrySet()) {
                double[] sp = rc[ci].get(base + "|COL@" + e.getKey());
                if (sp != null) {
                    t += e.getValue() * sp[0];
                }
            }
            int rows = rowSpan(n);
            if (rows > 0) {
                double[] sp = rc[ci].get(base + "|ROWS");
                if (sp != null) {
                    t += rows * sp[0];
                }
            }
            if (isSpanWire(n)) {
                hwireNodes++;
                double[] corr = hwireCorr.get(spanKey(n));
                if (corr != null) {
                    hwireCovered++;
                    t += corr[ci];
                }
            }
            }
        }
        return (float) t;
    }

    /** The priced route to a sink, one line per node with each term, for dissecting a mismatch. */
    public String explainNetDelay(Net net, SitePinInst sink, Corner corner) {
        StringBuilder sb = new StringBuilder();
        if (net.getSource() == null || sink == null) {
            return "no source or sink";
        }
        Routing routing = routingCache.computeIfAbsent(net, Routing::new);
        Node end = sink.getConnectedNode();
        if (end == null || !routing.parent.containsKey(end)) {
            return "sink not in routing";
        }
        int ci = corner == Corner.SLOW_MIN ? 1 : 0;
        double total = 0;
        List<Node> path = new ArrayList<>();
        for (Node n = end; n != null; n = routing.parent.get(n)) {
            path.add(n);
        }
        Collections.reverse(path);
        for (Node n : path) {
            String cls = wireClass(n);
            double[] drc = rc[ci].get(cls);
            double r = drc == null ? 0 : drc[0];
            double c = drc == null ? 0 : drc[1] * routing.subtreeC.get(n)[ci];
            double span = 0;
            for (Map.Entry<String, Integer> e : colSpanByType(n).entrySet()) {
                double[] sp = rc[ci].get(cls + "|COL@" + e.getKey());
                if (sp != null) {
                    span += e.getValue() * sp[0];
                }
            }
            int rows = rowSpan(n);
            if (rows > 0) {
                double[] sp = rc[ci].get(cls + "|ROWS");
                if (sp != null) {
                    span += rows * sp[0];
                }
            }
            double corr = 0;
            if (isSpanWire(n)) {
                double[] cv = hwireCorr.get(spanKey(n));
                if (cv != null) {
                    corr = cv[ci];
                }
            }
            total += r + c + span + corr;
            sb.append(String.format("   %7.1f  r %6.1f  c %6.1f (C=%.2f)  span %6.1f  corr %6.1f  sum %7.1f  %-14s %s%n",
                    r + c + span + corr, r, c, routing.subtreeC.get(n)[ci], span, corr, total, cls, n));
        }
        return sb.toString();
    }

    /** Forgets cached routing for a net; call after rerouting it. */
    public void invalidate(Net net) {
        routingCache.remove(net);
    }

    public void clearCache() {
        routingCache.clear();
    }

    /** What fraction of priced nodes had table entries, for reporting. */
    public String getCoverageSummary() {
        return String.format("nodes priced %d, unknown %d (%.1f%%); hwire corrections %d of %d (%.0f%%)",
                nodesPriced, nodesUnknown,
                100.0 * nodesUnknown / Math.max(1, nodesPriced + nodesUnknown),
                hwireCovered, hwireNodes, 100.0 * hwireCovered / Math.max(1, hwireNodes));
    }

    // ------------------------------------------------------------------
    // Loading and tree construction.
    // ------------------------------------------------------------------

    private static void readRC(Path table, Map<String, double[]> into) throws IOException {
        String section = null;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.equals("rc_delay")) {
                section = t;
                continue;
            }
            String[] f = t.split("\\s+");
            if (section == null || f.length < 4) {
                continue;
            }
            into.put(f[0], new double[] { Double.parseDouble(f[1]), Double.parseDouble(f[2]),
                    Double.parseDouble(f[3]) });
        }
    }

    private void readCorr(Path table) throws IOException {
        String section = null;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.equals("hwire_corr")) {
                section = t;
                continue;
            }
            String[] f = t.split("\\s+");
            if (section == null || f.length < 3) {
                continue;
            }
            // Under-sampled keys carry attribution noise, not signal.
            if (f.length > 3 && Double.parseDouble(f[3]) < 4) {
                continue;
            }
            hwireCorr.put(f[0], new double[] { Double.parseDouble(f[1]), Double.parseDouble(f[2]) });
        }
    }

    /** Forward BFS over the net's PIPs; see {@link VersalClockTimingModel#buildParents}. */
    private static Map<Node, Node> buildParents(Net net) {
        Map<Node, List<Node>> downhill = new HashMap<>();
        for (PIP p : net.getPIPs()) {
            downhill.computeIfAbsent(p.getStartNode(), k -> new ArrayList<>()).add(p.getEndNode());
        }
        Map<Node, Node> parent = new HashMap<>();
        Node source = net.getSource().getConnectedNode();
        if (source == null) {
            return parent;
        }
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(source);
        parent.put(source, null);
        Set<Node> seen = new HashSet<>();
        seen.add(source);
        // A net can leave its site through several pins (a flip-flop's Q,
        // the slice's mux output, the second FF's Q2); branches routed from
        // an alternate pin are unreachable from the primary one and every
        // sink on them would price null (slr_ring dropped 49k path
        // segments that way).
        for (SitePinInst alt : net.getAlternateSources()) {
            Node altNode = alt == null ? null : alt.getConnectedNode();
            if (altNode != null && seen.add(altNode)) {
                parent.put(altNode, null);
                queue.add(altNode);
            }
        }
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            for (Node c : downhill.getOrDefault(n, Collections.emptyList())) {
                if (seen.add(c)) {
                    parent.put(c, n);
                    queue.add(c);
                }
            }
        }
        return parent;
    }
}
