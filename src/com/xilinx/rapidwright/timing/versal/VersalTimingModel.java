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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.timing.DelayModel;
import com.xilinx.rapidwright.timing.DelayModelBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight net delay model for Versal devices, for one or more timing corners at once.
 *
 * <p>Interconnect delay is computed on the route tree of a net from the terms in
 * {@link VersalDelayTerms}; intra-site delay (driver BEL pin to site pin, site pin to sink BEL
 * pin) comes from the Versal {@link DelayModel} data files. Both are generated from Vivado data
 * by {@code fit_versal_model.py}, one pair of files per {@link VersalCorner}.
 *
 * <p>A model holds an ordered list of corners; every delay it returns is an array indexed by
 * corner position ({@link #indexOf(VersalCorner)}). The first corner is the "primary" one used by
 * the single-value convenience methods. {@code new VersalTimingModel(device)} loads all four
 * corners with slow-max first.
 */
public class VersalTimingModel {

    /** Delay breakdown for one sink of a net, in ps, one entry per corner of the model. */
    public static class SinkDelay {
        public float[] interconnect;
        public float[] driverIntraSite;
        public float[] sinkIntraSite;
        public boolean routed = true;

        SinkDelay(int corners) {
            interconnect = new float[corners];
            driverIntraSite = new float[corners];
            sinkIntraSite = new float[corners];
        }

        /** Total delay at corner index i. */
        public float getTotal(int i) {
            return interconnect[i] + driverIntraSite[i] + sinkIntraSite[i];
        }

        /** Total delay at the primary corner. */
        public float getTotal() {
            return getTotal(0);
        }

        /** Total delay at every corner. */
        public float[] getTotals() {
            float[] t = new float[interconnect.length];
            for (int i = 0; i < t.length; i++) t[i] = getTotal(i);
            return t;
        }

        @Override
        public String toString() {
            return String.format("%.0f ps (ic %.0f + drv %.0f + sink %.0f)", getTotal(), interconnect[0], driverIntraSite[0], sinkIntraSite[0]);
        }
    }

    private final Device device;
    private final VersalCorner[] corners;
    private final VersalDelayTerms[] terms;
    private final DelayModel[] delayModels;
    private final Map<Node, float[]> tileTermCache = new HashMap<>();
    private final Map<Node, String> signatureCache = new HashMap<>();
    private final Map<Node, String> signatureCacheFull = new HashMap<>();
    /** tile types treated as plain fabric (not part of a crossing signature); must match fit_versal_model.py */
    private static final java.util.Set<String> FABRIC_TILE_NAMES = new java.util.HashSet<>(java.util.Arrays.asList("INT", "CLE_E_CORE", "CLE_W_CORE", "SLL", "NULL"));
    private static final String[] FABRIC_TILE_PREFIXES = {"CLE_BC", "CBRK", "CPIPE", "RBRK"};

    static boolean isFabricTile(String tileType) {
        if (FABRIC_TILE_NAMES.contains(tileType)) return true;
        for (String p : FABRIC_TILE_PREFIXES) if (tileType.startsWith(p)) return true;
        return false;
    }

    /**
     * Crossing signature of a wire: the non-fabric tile types it spans with counts, sorted and
     * '+'-joined ("INTF_ROCF_TL_TILEx1+INTF_ROCF_TR_TILEx1"), or "-" for fabric only. Same
     * definition as crossing_signature() in fit_versal_model.py.
     */
    public String crossingSignature(Node n, boolean full) {
        Map<Node, String> cache = full ? signatureCacheFull : signatureCache;
        String cached = cache.get(n);
        if (cached != null) return cached;
        java.util.TreeMap<String, Integer> hist = new java.util.TreeMap<>();
        forEachTileBetween(device, n.getTile(), farthestTile(n), t -> {
            String tt = t.getTileTypeEnum().name();
            if (full || !isFabricTile(tt)) hist.merge(tt, 1, Integer::sum);
        });
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : hist.entrySet()) {
            if (sb.length() > 0) sb.append('+');
            sb.append(e.getKey()).append('x').append(e.getValue());
        }
        String sig = sb.length() == 0 ? "-" : sb.toString();
        cache.put(n, sig);
        return sig;
    }
    private final int[] edgeMisses = new int[1];
    private int intraSiteMisses = 0;
    private int intraSiteLookups = 0;
    private float intraSiteFallback = 60f;
    private final Map<String, Integer> missKeys = new HashMap<>();

    /** All four corners, slow-max first. */
    public VersalTimingModel(Device device) {
        this(device, VersalCorner.SLOW_MAX, VersalCorner.SLOW_MIN, VersalCorner.FAST_MAX, VersalCorner.FAST_MIN);
    }

    /** The given corners, in this order (the first is the primary corner). */
    public VersalTimingModel(Device device, VersalCorner... corners) {
        if (corners.length == 0) throw new IllegalArgumentException("at least one corner is required");
        this.device = device;
        this.corners = corners.clone();
        this.terms = new VersalDelayTerms[corners.length];
        this.delayModels = new DelayModel[corners.length];
        for (int i = 0; i < corners.length; i++) {
            terms[i] = new VersalDelayTerms(corners[i]);
            delayModels[i] = DelayModelBuilder.getDelayModel("versal", corners[i].getSuffix());
        }
    }

    /** A single-corner (slow-max) model from explicitly supplied terms and intra-site/logic delay model. */
    public VersalTimingModel(Device device, VersalDelayTerms terms, DelayModel delayModel) {
        this(device, VersalCorner.SLOW_MAX, terms, delayModel);
    }

    public VersalTimingModel(Device device, VersalCorner corner, VersalDelayTerms terms, DelayModel delayModel) {
        this.device = device;
        this.corners = new VersalCorner[] {corner};
        this.terms = new VersalDelayTerms[] {terms};
        this.delayModels = new DelayModel[] {delayModel};
    }

    public int getCornerCount() {
        return corners.length;
    }

    /** The corner at index i. */
    public VersalCorner getCorner(int i) {
        return corners[i];
    }

    /** The primary corner (index 0). */
    public VersalCorner getCorner() {
        return corners[0];
    }

    /** Index of a corner in this model, or -1. */
    public int indexOf(VersalCorner c) {
        for (int i = 0; i < corners.length; i++) if (corners[i] == c) return i;
        return -1;
    }

    /** Whether the corner at index i is a max-delay (setup) corner. */
    public boolean isMax(int i) {
        return corners[i].isMax();
    }

    /** Terms of the primary corner. */
    public VersalDelayTerms getTerms() {
        return terms[0];
    }

    public VersalDelayTerms getTerms(int i) {
        return terms[i];
    }

    /** Intra-site / logic delay model of the primary corner. */
    public DelayModel getDelayModel() {
        return delayModels[0];
    }

    public DelayModel getDelayModel(int i) {
        return delayModels[i];
    }

    // ----------------------------------------------------------------------------- interconnect

    /** Interconnect-only arrival (ps) at every reachable node of the net's route tree, primary corner. */
    public Map<Node, Float> calcNodeArrivals(Net net) {
        return calcNodeArrivals(net, null);
    }

    /** As {@link #calcNodeArrivals(Net)} with the root of each node's subtree recorded in {@code rootOf}. */
    public Map<Node, Float> calcNodeArrivals(Net net, Map<Node, Node> rootOf) {
        Map<Node, Float> out = new LinkedHashMap<>();
        for (Map.Entry<Node, float[]> e : calcNodeArrivalsAllCorners(net, rootOf).entrySet()) out.put(e.getKey(), e.getValue()[0]);
        return out;
    }

    /**
     * Interconnect-only arrival (ps, one value per corner) at every node of the net's route tree,
     * relative to the source pin node of its subtree. A net can have more than one source site pin
     * (e.g. a LUT leaving its slice through two output pins): every PIP start node without an
     * incoming PIP is a root at arrival 0. If {@code rootOf} is non-null it receives the root of
     * every reached node.
     */
    public Map<Node, float[]> calcNodeArrivalsAllCorners(Net net, Map<Node, Node> rootOf) {
        int nc = corners.length;
        Map<Node, float[]> arrivals = new LinkedHashMap<>();
        Map<Node, List<Node>> children = new HashMap<>();
        Map<Node, Node> parentOf = new HashMap<>();
        Set<Node> ends = new HashSet<>();
        for (PIP pip : net.getPIPs()) {
            Node s = pip.getStartNode(), e = pip.getEndNode();
            if (s == null || e == null) continue;
            children.computeIfAbsent(s, k -> new ArrayList<>(2)).add(e);
            parentOf.putIfAbsent(e, s);
            ends.add(e);
        }
        List<Node> roots = new ArrayList<>(2);
        SitePinInst src = net.getSource();
        if (src != null && src.getConnectedNode() != null) roots.add(src.getConnectedNode());
        for (Node s : children.keySet()) if (!ends.contains(s) && !roots.contains(s)) roots.add(s);

        Deque<Node> stack = new ArrayDeque<>();
        for (Node root : roots) {
            if (arrivals.containsKey(root)) continue;
            arrivals.put(root, new float[nc]);
            if (rootOf != null) rootOf.put(root, root);
            stack.push(root);
            while (!stack.isEmpty()) {
                Node p = stack.pop();
                List<Node> kids = children.get(p);
                if (kids == null) continue;
                float[] pArr = arrivals.get(p);
                float[] tile = tileTerm(p);
                IntentCode pi = p.getIntentCode();
                String pClass = parentClass(p);
                boolean hasTileTerms = false;
                for (VersalDelayTerms t : terms) hasTileTerms |= t.hasTileTerms(pi);
                String signature = hasTileTerms ? crossingSignature(p, false) : null, signatureFull = hasTileTerms ? crossingSignature(p, true) : null;
                List<IntentCode> sibIntents = new ArrayList<>(kids.size());
                for (Node k : kids) sibIntents.add(k.getIntentCode());
                String sibKey = VersalDelayTerms.childKey(sibIntents);
                Node gp = parentOf.get(p);
                IntentCode gpi = gp == null ? null : gp.getIntentCode();
                int gpFanout = gp == null ? 0 : children.get(gp).size();
                for (Node c : kids) {
                    if (arrivals.containsKey(c)) continue; // loops / duplicate PIPs
                    List<Node> grand = children.get(c);
                    List<IntentCode> grandIntents = new ArrayList<>(grand == null ? 0 : grand.size());
                    if (grand != null) for (Node g : grand) grandIntents.add(g.getIntentCode());
                    String childKey = VersalDelayTerms.childKey(grandIntents);
                    IntentCode ci = c.getIntentCode();
                    int extra = grand != null && grand.size() > 1 ? Math.min(VersalDelayTerms.MAX_FANOUT_CHILDREN, grand.size() - 1) : 0;
                    float[] d = new float[nc];
                    for (int i = 0; i < nc; i++) {
                        d[i] = pArr[i] + tile[i] + terms[i].edgeDelay(pClass, pi, ci, childKey, sibKey, kids.size(), i == 0 ? edgeMisses : null);
                        if (extra > 0) d[i] += terms[i].fanoutTerm(ci) * extra;
                        if (gpi != null) d[i] += terms[i].grandparentTerm(gpi, pi, ci, gpFanout);
                        if (signature != null) d[i] += terms[i].crossingCorrection(pClass, pi, ci, signatureFull, signature);
                    }
                    arrivals.put(c, d);
                    if (rootOf != null) rootOf.put(c, root);
                    stack.push(c);
                }
            }
        }
        return arrivals;
    }

    private static final Pattern H_WIRE = Pattern.compile("^(?:OUT|IN)_([EW]{2})\\d+(?:_([EW]))?");

    /**
     * Wire family of a horizontal wire node: direction and INT-tile half (EE_E, WW, RED, ...), or ""
     * if the wire name has no such structure. Must match h_family() in fit_versal_model.py.
     */
    public static String hFamily(String wireName) {
        if (wireName.startsWith("INT_SDQ_RED")) return "RED";
        Matcher m = H_WIRE.matcher(wireName);
        if (!m.find()) return "";
        return m.group(1) + (m.group(2) != null ? "_" + m.group(2) : "");
    }

    /** Parent class for the EDGE table: intent name, plus ":family" for horizontal wires. */
    public static String parentClass(Node n) {
        IntentCode ic = n.getIntentCode();
        switch (ic) {
            case NODE_HSINGLE: case NODE_HDOUBLE: case NODE_HQUAD: case NODE_HLONG6: case NODE_HLONG10: {
                String fam = hFamily(n.getTile().getWireName(n.getWireIndex()));
                return fam.isEmpty() ? ic.name() : ic.name() + ":" + fam;
            }
            case NODE_DEDICATED:
                return ic.name() + ":" + dedicatedFamily(n.getTile().getWireName(n.getWireIndex()));
            default:
                return ic.name();
        }
    }

    /**
     * Wire family of a dedicated node: the wire name with digits removed (CLE carry COUT/CIN, DSP and
     * BRAM cascades, XPHY/XPIO pins differ by an order of magnitude). Must match dedicated_family()
     * in fit_versal_model.py.
     */
    public static String dedicatedFamily(String wireName) {
        String f = wireName.replaceAll("[0-9]+", "").replaceAll("_+", "_");
        if (f.startsWith("_")) f = f.substring(1);
        if (f.endsWith("_")) f = f.substring(0, f.length() - 1);
        return f;
    }

    /**
     * The tile of the node's wire that is farthest (Manhattan distance in tile rows/columns) from
     * the node's base tile: the far end of the wire segment.
     */
    public static Tile farthestTile(Node n) {
        Tile a = n.getTile(), best = a;
        int bestD = 0;
        for (Wire w : n.getAllWiresInNode()) {
            Tile t = w.getTile();
            int d = Math.abs(t.getColumn() - a.getColumn()) + Math.abs(t.getRow() - a.getRow());
            if (d > bestD) { bestD = d; best = t; }
        }
        return best;
    }

    /**
     * Visits the tiles strictly between two tiles along their shared row or column (the tiles a
     * straight wire crosses). For tiles sharing neither, the L-shaped walk along a's row to b's
     * column and then along that column is visited.
     */
    public static void forEachTileBetween(Device device, Tile a, Tile b, Consumer<Tile> visitor) {
        if (a.getRow() == b.getRow()) {
            int lo = Math.min(a.getColumn(), b.getColumn()), hi = Math.max(a.getColumn(), b.getColumn());
            for (int col = lo + 1; col < hi; col++) {
                Tile t = device.getTile(a.getRow(), col);
                if (t != null) visitor.accept(t);
            }
        } else if (a.getColumn() == b.getColumn()) {
            int lo = Math.min(a.getRow(), b.getRow()), hi = Math.max(a.getRow(), b.getRow());
            for (int row = lo + 1; row < hi; row++) {
                Tile t = device.getTile(row, a.getColumn());
                if (t != null) visitor.accept(t);
            }
        } else {
            Tile corner = device.getTile(a.getRow(), b.getColumn());
            if (corner == null) return;
            forEachTileBetween(device, a, corner, visitor);
            visitor.accept(corner);
            forEachTileBetween(device, corner, b, visitor);
        }
    }

    /** Sum of TILE terms (per corner) for the tiles a node's wires span, cached per node. */
    private float[] tileTerm(Node n) {
        IntentCode ic = n.getIntentCode();
        boolean any = false;
        for (VersalDelayTerms t : terms) any |= t.hasTileTerms(ic);
        if (!any) return new float[corners.length];
        float[] cached = tileTermCache.get(n);
        if (cached != null) return cached;
        float[] sum = new float[corners.length];
        forEachTileBetween(device, n.getTile(), farthestTile(n), t -> {
            for (int i = 0; i < corners.length; i++) sum[i] += terms[i].tileTerm(ic, t.getTileTypeEnum());
        });
        tileTermCache.put(n, sum);
        return sum;
    }

    // -------------------------------------------------------------------------------- net delays

    /**
     * Delay (per corner) from the net's source to each of its sink site pins, including the
     * intra-site portions on both ends: the quantity Vivado reports as the net delay.
     */
    public Map<SitePinInst, SinkDelay> calcNetDelays(Net net) {
        int nc = corners.length;
        Map<SitePinInst, SinkDelay> result = new LinkedHashMap<>();
        Map<Node, Node> rootOf = new HashMap<>();
        Map<Node, float[]> arrivals = calcNodeArrivalsAllCorners(net, rootOf);
        if (arrivals.isEmpty()) return result;
        // driver-side intra-site delay per source pin (a net may leave its site through several pins)
        Map<Node, float[]> driverDelay = new HashMap<>();
        for (SitePinInst spi : net.getPins()) {
            if (!spi.isOutPin()) continue;
            Node n = spi.getConnectedNode();
            if (n != null && arrivals.containsKey(n) && n.equals(rootOf.get(n))) driverDelay.put(n, driverIntraSiteDelays(spi, net));
        }
        for (SitePinInst sink : net.getSinkPins()) {
            SinkDelay sd = new SinkDelay(nc);
            Node n = sink.getConnectedNode();
            float[] arr = n == null ? null : arrivals.get(n);
            if (arr == null) {
                sd.routed = false;
            } else {
                sd.interconnect = arr.clone();
                float[] drv = driverDelay.get(rootOf.get(n));
                if (drv != null) sd.driverIntraSite = drv.clone();
            }
            sd.sinkIntraSite = sinkIntraSiteDelays(sink);
            result.put(sink, sd);
        }
        return result;
    }

    /** Total delay (ps, primary corner) from the net source to one sink, or -1 if the sink is unrouted. */
    public float calcDelay(SitePinInst sink, Net net) {
        SinkDelay sd = calcNetDelays(net).get(sink);
        return sd == null || !sd.routed ? -1f : sd.getTotal();
    }

    // --------------------------------------------------------------------------------- intra-site

    /** Delay from the driving cell's BEL output pin to the output site pin, primary corner. */
    public float driverIntraSiteDelay(SitePinInst src) {
        return driverIntraSiteDelays(src, null)[0];
    }

    /**
     * Delay (per corner) from the driving cell's BEL output pin to the output site pin. When the
     * site routing does not lead back to a cell (e.g. a LUT leaving through the flop bypass), the
     * net's logical source cell is used.
     */
    public float[] driverIntraSiteDelays(SitePinInst src, Net net) {
        SiteInst si = src.getSiteInst();
        if (si == null) return new float[corners.length];
        List<String[]> keys = new ArrayList<>(2);
        for (BELPin bp : DesignTools.getConnectedBELPins(src)) {
            if (!bp.isOutput()) continue;
            if (si.getCell(bp.getBEL()) == null) continue;
            keys.add(new String[] {bp.getBEL().getName() + "/" + bp.getName(), src.getName()});
        }
        for (Cell c : DesignTools.getConnectedCells(src)) {
            if (c.getBEL() == null) continue;
            for (String phys : c.getPinMappingsP2L().keySet()) {
                BELPin bp = c.getBEL().getPin(phys);
                if (bp != null && bp.isOutput()) keys.add(new String[] {bp.getBEL().getName() + "/" + phys, src.getName()});
            }
        }
        if (net != null && si.getDesign() != null) {
            EDIFHierNet hnet = si.getDesign().getNetlist().getHierNetFromName(net.getName());
            if (hnet != null) {
                for (EDIFHierPortInst p : hnet.getLeafHierPortInsts(true, false)) {
                    Cell c = p.getPhysicalCell(si.getDesign());
                    if (c == null || c.getSiteInst() != si || c.getBEL() == null) continue;
                    String phys = c.getPhysicalPinMapping(p.getPortInst().getName());
                    if (phys != null) keys.add(new String[] {c.getBELName() + "/" + phys, src.getName()});
                }
            }
        }
        return lookupFirst(si, keys, "driver " + src.getName() + " <- " + DesignTools.getConnectedCells(src));
    }

    /** Delay from the input site pin to the BEL input pin of the cell it feeds, primary corner. */
    public float sinkIntraSiteDelay(SitePinInst sink) {
        return sinkIntraSiteDelays(sink)[0];
    }

    /** Delay (per corner) from the input site pin to the BEL input pin of the cell it feeds. */
    public float[] sinkIntraSiteDelays(SitePinInst sink) {
        SiteInst si = sink.getSiteInst();
        if (si == null) return new float[corners.length];
        List<String[]> keys = new ArrayList<>(2);
        for (BELPin bp : DesignTools.getConnectedBELPins(sink)) {
            if (!bp.isInput()) continue;
            if (si.getCell(bp.getBEL()) == null) continue;
            keys.add(new String[] {sink.getName(), bp.getBEL().getName() + "/" + bp.getName()});
        }
        return lookupFirst(si, keys, "sink " + sink.getName());
    }

    /** Delay between two BEL pins inside one site (intra-site net), primary corner. */
    public float intraSiteNetDelay(SiteInst si, BELPin from, BELPin to) {
        return intraSiteNetDelays(si, from, to)[0];
    }

    /** Delay (per corner) between two BEL pins inside one site (intra-site net). */
    public float[] intraSiteNetDelays(SiteInst si, BELPin from, BELPin to) {
        List<String[]> keys = new ArrayList<>(1);
        keys.add(new String[] {from.getBEL().getName() + "/" + from.getName(), to.getBEL().getName() + "/" + to.getName()});
        return lookupFirst(si, keys, "intra " + keys.get(0)[0] + " -> " + keys.get(0)[1]);
    }

    /**
     * Looks the candidate (from, to) keys up in the primary corner's model, in order, and returns
     * the delays of all corners for the first key found (with the letter fallback), or the
     * fallback delay everywhere with a miss recorded.
     */
    private float[] lookupFirst(SiteInst si, List<String[]> keys, String missKey) {
        intraSiteLookups++;
        for (String[] k : keys) {
            String from = k[0], to = k[1];
            Short v = lookup(0, si, from, to);
            if (v == null) {
                // Slice pins are replicated per LUT/FF letter (A..H); fall back to the 'A' instance, then to
                // any letter that was sampled (e.g. a LUTRAM WE pin seen only on F6LUT/H6LUT).
                String from2 = toLetterA(from), to2 = toLetterA(to);
                if (isLettered(from2) || isLettered(to2)) {
                    for (char c = 'A'; c <= 'H' && v == null; c++) {
                        String f3 = withLetter(from2, c), t3 = withLetter(to2, c);
                        v = lookup(0, si, f3, t3);
                        if (v != null) { from = f3; to = t3; }
                    }
                }
            }
            if (v == null) continue;
            float[] out = new float[corners.length];
            out[0] = v;
            for (int i = 1; i < corners.length; i++) {
                Short vi = lookup(i, si, from, to);
                out[i] = vi == null ? v : vi;
            }
            return out;
        }
        intraSiteMisses++;
        missKeys.merge(missKey.length() > 80 ? missKey.substring(0, 80) : missKey, 1, Integer::sum);
        float[] out = new float[corners.length];
        Arrays.fill(out, intraSiteFallback);
        return out;
    }

    private Short lookup(int corner, SiteInst si, String from, String to) {
        try {
            Short v = delayModels[corner].getIntraSiteDelay(si.getSiteTypeEnum(), from, to);
            return v == null || v == -1 ? null : v;
        } catch (IllegalArgumentException e) {
            return null; // unknown site type for this model
        }
    }

    /** True for a slice pin name in letter-A form that exists once per LUT/FF letter ("A3", "AFF/Q", "A6LUT/WE"). */
    static boolean isLettered(String pinA) {
        return pinA.matches("^A(\\d|X|Q|_|MUX|FF|[56]LUT|CY|\\d_IMR).*");
    }

    /** The letter-A form of a slice pin name with its letter replaced by {@code c}; other names unchanged. */
    static String withLetter(String pinA, char c) {
        return isLettered(pinA) ? c + pinA.substring(1) : pinA;
    }

    /** Maps slice pin names of letter B..H onto letter A (e.g. "C3", "CFF/Q", "H6LUT/A4"). */
    static String toLetterA(String pin) {
        if (pin.isEmpty() || pin.charAt(0) < 'B' || pin.charAt(0) > 'H') return pin;
        if (pin.matches("^[B-H](\\d|X|Q|_|MUX|FF|[56]LUT|CY|\\d_IMR).*")) return "A" + pin.substring(1);
        return pin;
    }

    public void setIntraSiteFallback(float ps) {
        intraSiteFallback = ps;
    }

    public int getEdgeMissCount() {
        return edgeMisses[0];
    }

    public int getIntraSiteMissCount() {
        return intraSiteMisses;
    }

    public int getIntraSiteLookupCount() {
        return intraSiteLookups;
    }

    /** Intra-site lookups that fell back to the default, by key, for diagnostics. */
    public Map<String, Integer> getIntraSiteMissKeys() {
        return missKeys;
    }
}
