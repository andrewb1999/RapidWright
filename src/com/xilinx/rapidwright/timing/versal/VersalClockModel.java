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
import com.xilinx.rapidwright.design.BELAttr;
import com.xilinx.rapidwright.design.SiteConfig;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.util.FileTools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Clock arrival model for Versal: reproduces Vivado's root-sum-square clock propagation.
 *
 * <p>Vivado prices a routed clock net by accumulating, at every node of the route tree, a mean and a
 * variance per corner; the arrival is {@code sum(means) + sqrt(sum(variances))} at max-delay corners
 * and {@code sum(means) - sqrt(sum(variances))} at min-delay corners. The step of a node depends on
 * the node, the PIP it was entered by and the PIP it leaves by (its context). The data file
 * {@code timing/versal/clock_delay_terms.txt} (from {@code fit_clock_model.py}) holds exact steps
 * for every context observed in traced designs, a fallback per physical class (tile type, wire and
 * PIP names without coordinates, fanout) and coarser fallbacks, plus the intra-site clock delay from
 * the site pin to the flop's clock pin.
 *
 * <p>Corner order everywhere: slow max, slow min, fast max, fast min.
 */
public class VersalClockModel {

    public static final String DEFAULT_FILE = "timing" + File.separator + "versal" + File.separator + "clock_delay_terms.txt";
    public static final String[] CORNERS = {"slow_max", "slow_min", "fast_max", "fast_min"};

    private final Map<String, float[]> ctx = new HashMap<>();
    private final Map<String, float[]> cls = new HashMap<>();
    private final Map<String, float[]> cls2 = new HashMap<>();
    private final Map<String, float[]> cls3 = new HashMap<>();
    private final Map<String, float[]> clkSite = new HashMap<>();
    /** extra delay of a sink reached through implicit hops: Vivado's own estimate for the leaf segment a checkpoint does not route (untraced) */
    private final Map<String, float[]> clkImplicit = new HashMap<>();
    /** extra intra-site clock delay when the slice's FF_CLK_MOD is in DELAY mode with n taps: "mode\ttaps" -> 4 delays */
    private final Map<String, float[]> clkTap = new HashMap<>();
    private final Map<String, Integer> tierUse = new LinkedHashMap<>();
    private static final boolean DEBUG_TIERS = System.getenv("DEBUG_CLOCK_TIERS") != null;
    private static final int DEBUG_LIMIT = DEBUG_TIERS && System.getenv("DEBUG_CLOCK_TIERS").matches("\\d+") ? Integer.parseInt(System.getenv("DEBUG_CLOCK_TIERS")) : 40;
    private int debugShown = 0;
    /** track-free family tiers: FAMDY/FAMDX (tier \t node family \t in family \t out family \t child offset) and FAM */
    private final Map<String, float[]> famGeo = new HashMap<>();
    /** track-free exact contexts of the leaf level: node / pips with tile coordinates kept and digit runs replaced by '#' */
    private final Map<String, float[]> tctx = new HashMap<>();
    private static final java.util.Set<String> TRACK_FREE = new java.util.HashSet<>(java.util.Arrays.asList(
            "CLK_LEAF_SITES_#_I", "CLK_LEAF_SITES_#_O", "CLK_LEAF_SITES_#_O_PIN", "CNODE_OUTS_E#", "CNODE_OUTS_W#",
            "CTRL_L_B#", "CTRL_R_B#", "CLKE#_PD_OPT_DELAY_SSIT_#_O", "CLKE#_PD_OPT_DELAY_SSIT_#_O_PIN"));
    private final Map<String, float[]> fam = new HashMap<>();
    /** wire-name prefix -> per-corner coefficient c: the step of such a node at fanout k is the table step * (1 + c * (k - 1)) */
    private final Map<String, float[]> fanScale = new HashMap<>();
    /** wire-name prefix -> per-corner coefficient c applied with the PARENT node's fanout (the hop into the slice clock pin) */
    private final Map<String, float[]> fanScaleParent = new HashMap<>();
    /** programmed RCLK leaf delay (BUFDIV_LCLK_DELAY CONFIG.DELAY VAL4/VAL8) -> per-corner offset of the O_PIN hop; tables hold VAL0 */
    private final Map<String, float[]> leafDly = new HashMap<>();
    /** BUFDIV_LEAF site name -> VALn, from a side file (Vivado's view; RapidWright does not read these BEL configs) */
    private final Map<String, String> leafDelayOfSite = new HashMap<>();
    private com.xilinx.rapidwright.design.Design currentDesign;
    private static final java.util.regex.Pattern LEAF_OPIN = java.util.regex.Pattern.compile("CLK_LEAF_SITES_(\\d+)_O_PIN");

    /** Load "site,bel,delay" rows (dump_clock_trace.tcl writes <run>.leafdelay.csv) giving the programmed leaf delays. */
    public void loadLeafDelays(String csv) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(csv))) {
            String line = br.readLine();
            while ((line = br.readLine()) != null) {
                String[] f = line.split(",");
                if (f.length >= 3 && f[1].contains("LCLK_DELAY")) leafDelayOfSite.put(f[0], f[2].trim());
            }
        }
    }

    public int getLeafDelayCount() { return leafDelayOfSite.size(); }

    /** VAL0/VAL4/VAL8 of the leaf delay element behind an O_PIN node when the incoming PIP is not known: the BEL attribute when RapidWright
     *  has it, else the side file, else VAL0. The k-th BUFDIV_LEAF site of the RCLK tile (by name) is leaf k. */
    String leafLevel(Node n) {
        java.util.regex.Matcher m = LEAF_OPIN.matcher(n.getWireName());
        if (!m.matches()) return null;
        int k = Integer.parseInt(m.group(1));
        List<com.xilinx.rapidwright.device.Site> sites = new ArrayList<>();
        for (com.xilinx.rapidwright.device.Site s : n.getTile().getSites()) if (s.getSiteTypeEnum().name().startsWith("BUFDIV_LEAF")) sites.add(s);
        sites.sort((a, b) -> Integer.compare(a.getInstanceY(), b.getInstanceY()));
        if (k >= sites.size()) return "VAL0";
        com.xilinx.rapidwright.device.Site site = sites.get(k);
        if (currentDesign != null) {
            Map<com.xilinx.rapidwright.device.Site, SiteConfig> attrs = currentDesign.getBELAttrs();
            SiteConfig sc = attrs == null ? null : attrs.get(site);
            if (sc != null) {
                for (Map.Entry<com.xilinx.rapidwright.device.BEL, Map<String, BELAttr>> e : sc.getBELAttributes().entrySet())
                    for (BELAttr a : e.getValue().values()) if (a.getName().endsWith("DELAY") && a.getValue().startsWith("VAL")) return a.getValue();
            }
        }
        return leafDelayOfSite.getOrDefault(site.getName(), "VAL0");
    }
    private int siteMisses = 0;
    /** Vivado's delay buckets: each step's moments are accumulated in one of them */
    public static final String[] BUCKETS = {"Local", "GlobalX", "GlobalY"};
    /** state layout: [0,8) totals (4 means, 4 variances), then 8 moments per bucket */
    public static final int STATE_SIZE = 8 + 8 * BUCKETS.length;
    /** VT-drift skew coefficients per corner: fX, fY, fLocal (see fit_clock_model.py) */
    private final float[][] vtDrift = new float[4][3];
    /** a node's own wire delay (4 corners) by node class, and by intent as fallback */
    private final Map<String, float[]> wire = new HashMap<>();
    private final Map<String, float[]> wire2 = new HashMap<>();

    public VersalClockModel() {
        this(FileTools.getRapidWrightPath() + File.separator + DEFAULT_FILE);
    }

    /**
     * Opens a table file: {@code fileName} if it exists, otherwise {@code fileName + ".gz"} (the
     * table is shipped gzipped; a fresh fit writes the plain file next to it and takes precedence).
     */
    static BufferedReader openTable(String fileName) throws IOException {
        File plain = new File(fileName);
        if (plain.exists() && !fileName.endsWith(".gz")) return new BufferedReader(new FileReader(plain));
        File gz = fileName.endsWith(".gz") ? plain : new File(fileName + ".gz");
        if (!gz.exists()) throw new java.io.FileNotFoundException(fileName + " (or .gz)");
        return new BufferedReader(new java.io.InputStreamReader(
                new java.util.zip.GZIPInputStream(new java.io.FileInputStream(gz), 1 << 16)));
    }

    public VersalClockModel(String fileName) {
        try (BufferedReader br = openTable(fileName)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split(" ");
                switch (f[0]) {
                    case "CTX": ctx.put(key(f[1], f[2], f[3]), moments(f, 4)); break;
                    case "CLASS": cls.put(key(f[1], f[2], f[3]) + "\t" + f[4], moments(f, 5)); break;
                    case "CLASS2": cls2.put(key(f[1], f[2], f[3]), moments(f, 4)); break;
                    case "CLASS3": cls3.put(f[1], moments(f, 2)); break;
                    case "FAMDY": case "FAMDX": famGeo.put(f[0] + "\t" + f[1] + "\t" + f[2] + "\t" + f[3] + "\t" + f[4], moments(f, 5)); break;
                    case "FAM": fam.put(f[1] + "\t" + f[2] + "\t" + f[3], moments(f, 4)); break;
                    case "TCTX": tctx.put(f[1] + "\t" + f[2] + "\t" + f[3], moments(f, 4)); break;
                    case "WIRE": case "WIRE2": {
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[2 + i]);
                        (f[0].equals("WIRE") ? wire : wire2).put(f[1], d);
                        break;
                    }
                    case "VTDRIFT": {
                        int c = java.util.Arrays.asList(CORNERS).indexOf(f[1]);
                        if (c >= 0) for (int i = 0; i < 3; i++) vtDrift[c][i] = Float.parseFloat(f[2 + i]);
                        break;
                    }
                    case "CLKSITE": {
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[4 + i]);
                        clkSite.put(f[1] + "\t" + f[2] + "\t" + f[3], d);
                        break;
                    }
                    case "CLKIMPLICIT": {
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[3 + i]);
                        clkImplicit.put(f[1] + "\t" + f[2], d);
                        break;
                    }
                    case "CLKTAP": {
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[3 + i]);
                        clkTap.put(f[1] + "\t" + f[2], d);
                        break;
                    }
                    case "LEAFDLY": {
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[2 + i]);
                        leafDly.put(f[1], d);
                        break;
                    }
                    case "FANSCALE": {
                        // FANSCALE <wire prefix> <self|parent> <4 coefficients>; older tables omit the self|parent word
                        boolean named = f[2].equals("self") || f[2].equals("parent");
                        float[] d = new float[4];
                        for (int i = 0; i < 4; i++) d[i] = Float.parseFloat(f[(named ? 3 : 2) + i]);
                        (named && f[2].equals("parent") ? fanScaleParent : fanScale).put(f[1], d);
                        break;
                    }
                    default: throw new RuntimeException("Unknown keyword in " + fileName + ": " + line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read Versal clock terms from " + fileName, e);
        }
    }

    private static String key(String a, String b, String c) {
        return (a.equals("-") ? "" : a) + "\t" + (b.equals("-") ? "" : b) + "\t" + (c.equals("-") ? "" : c);
    }

    /** 8 step moments followed by the bucket index (0 Local, 1 GlobalX, 2 GlobalY; Local when absent). */
    private static float[] moments(String[] f, int from) {
        float[] m = new float[9];
        for (int i = 0; i < 8; i++) m[i] = Float.parseFloat(f[from + i]);
        int b = 0;
        if (f.length > from + 8) {
            for (int i = 0; i < BUCKETS.length; i++) if (BUCKETS[i].equals(f[from + 8])) b = i;
        }
        m[8] = b;
        return m;
    }

    /** A full-size state whose totals are the given (at least 8) moments and whose buckets are empty. */
    static float[] expand(float[] totals) {
        float[] s = new float[STATE_SIZE];
        System.arraycopy(totals, 0, s, 0, Math.min(8, totals.length));
        return s;
    }

    // ----------------------------------------------------------------------- physical keys

    static String tileMask(String tileName) {
        return tileName.replaceFirst("_X\\d+Y\\d+$", "");
    }

    /** Node key of the family tiers: tile type + "/" + wire with digit runs replaced by '#' (track-free). */
    static String famKey(Node n) {
        return tileMask(n.getTile().getName()) + "/" + n.getWireName().replaceAll("\\d+", "#");
    }

    /** Track-free exact key: tile coordinates kept, digit runs of the wire / pip names replaced by '#'; null for trunk nodes. */
    static String trackFreeKey(Node n, PIP in, PIP out) {
        String wf = n.getWireName().replaceAll("\\d+", "#");
        if (!TRACK_FREE.contains(wf)) return null;
        String o = trackFreePip(out);
        // a CNODE's step does not depend on which side (CTRL_L_* / CTRL_R_*) it drives: one exact row serves both
        if (wf.startsWith("CNODE_OUTS")) o = o.replace("CTRL_L_", "CTRL_?_").replace("CTRL_R_", "CTRL_?_");
        return n.getTile().getName() + "/" + wf + "\t" + trackFreePip(in) + "\t" + o;
    }

    static String trackFreePip(PIP p) {
        if (p == null) return "";
        String s = p.toString();
        int dot = s.indexOf('.', s.indexOf('/'));
        return s.substring(0, s.indexOf('/')) + "/" + s.substring(dot + 1).replaceAll("\\d+", "#");
    }

    static String famPip(PIP p) {
        if (p == null) return "";
        String s = p.toString();
        int slash = s.indexOf('/');
        int dot = s.indexOf('.', slash);
        return tileMask(s.substring(0, slash)) + "/" + s.substring(dot + 1).replaceAll("\\d+", "#");
    }

    /** "dy" for the RCLK leaf drivers (child row offset), "dx" for the row entry / row segments (child column offset), "" otherwise. */
    static String famTier(Node n) {
        String wire = n.getWireName().replaceAll("\\d+", "#");
        if (wire.equals("CLK_LEAF_SITES_#_O")) return "dy";
        IntentCode ic = n.getIntentCode();
        if (ic == IntentCode.NODE_GLOBAL_HDISTR_LOCAL || wire.startsWith("IF_HCLK_R_CLK_HDISTR") || wire.equals("CLKE#_PD_OPT_DELAY_SSIT_#_O")) return "dx";
        return "";
    }

    /** Node key of the CLASS tiers: tile name without coordinates + "/" + wire. */
    static String nodeKey(Node n) {
        String s = n.toString();
        int slash = s.indexOf('/');
        return tileMask(s.substring(0, slash)) + s.substring(slash);
    }

    /** PIP key of the CLASS tiers: tile name without coordinates + "/" + "start->>end". */
    static String pipKey(PIP p) {
        if (p == null) return "";
        String s = p.toString();
        int slash = s.indexOf('/'), dot = s.indexOf('.', slash);
        return tileMask(s.substring(0, slash)) + "/" + s.substring(dot + 1);
    }

    private static String pipName(PIP p) {
        return p == null ? "" : p.toString();
    }

    // ----------------------------------------------------------------------- route tree

    /** The route tree of a clock net and the accumulated clock states along it. */
    public static class ClockTree {
        public final Net net;
        public final Map<Node, Node> parent = new HashMap<>();
        public final Map<Node, PIP> parentPip = new HashMap<>();
        public final Map<Node, List<Node>> children = new HashMap<>();
        public int merged;   // nodes folded into their driver (entered through an unbuffered PIP)
        public final Map<Node, List<PIP>> childPips = new HashMap<>();
        public final List<Node> roots = new ArrayList<>();
        /** state of a node toward each child (moments: 4 means, 4 variances) */
        public final Map<Node, Map<Node, float[]>> stateToward = new HashMap<>();
        /** state of a leaf node (sink pin) */
        public final Map<Node, float[]> leafState = new HashMap<>();
        /** state arriving at a node (its parent's state toward it, or the root state) */
        public final Map<Node, float[]> arriving = new HashMap<>();
        /** interconnect arrival at every sink site pin (4 corners) */
        public final Map<SitePinInst, float[]> sinkArrival = new LinkedHashMap<>();
        /** arrival at the flop/BEL clock pin including the intra-site part */
        public final Map<SitePinInst, float[]> pinArrival = new LinkedHashMap<>();
        public int unpricedNodes = 0;
        /** hops added for sinks the net's PIPs do not reach (see analyze) */
        public int implicitHops = 0;
        /** nodes attached by implicit hops (hard-block clock pins the checkpoint has no PIPs into) */
        public final Set<Node> implicitNodes = new HashSet<>();

        ClockTree(Net net) {
            this.net = net;
        }

        /** Root-sum-square arrival (4 corners) of a state. */
        public static float[] arrival(float[] s) {
            float[] a = new float[4];
            for (int i = 0; i < 4; i++) a[i] = s[i] + (i % 2 == 0 ? 1 : -1) * (float) Math.sqrt(Math.max(0, s[4 + i]));
            return a;
        }

        /** Arrival at a node as seen along the branch toward the given descendant (or its leaf state). */
        public float[] arrivalAt(Node n, Node towardChild) {
            float[] s = towardChild == null ? leafState.get(n) : stateToward.getOrDefault(n, java.util.Collections.emptyMap()).get(towardChild);
            if (s == null && !stateToward.getOrDefault(n, java.util.Collections.emptyMap()).isEmpty())
                s = stateToward.get(n).values().iterator().next();
            return s == null ? null : arrival(s);
        }

        /** Path from a node up to its root, nearest first. */
        public List<Node> pathToRoot(Node n) {
            List<Node> p = new ArrayList<>();
            for (Node x = n; x != null; x = parent.get(x)) p.add(x);
            return p;
        }
    }

    private final Map<Net, float[]> rootStates = new HashMap<>();
    private final Map<Net, String> rootDescriptions = new LinkedHashMap<>();

    /** Human-readable breakdown of the root state of every net priced so far (for diagnostics). */
    public Map<Net, String> getRootDescriptions() {
        return rootDescriptions;
    }

    /**
     * Clock state (8 moments) at the source pin of a net: the arrival at the driving buffer's input
     * (recursively through the net that feeds it, a global clock net or an ordinary routed net, or 0
     * at a port), plus the buffer's input-to-output arc and its output intra-site delay. Needs the
     * data-path model for the arcs and intra-site terms; null gives a zero root.
     */
    public float[] rootState(Net net, VersalTimingModel dm) {
        if (dm == null) return new float[8];
        float[] cached = rootStates.get(net);
        if (cached != null) return cached;
        float[] state = new float[8];
        rootStates.put(net, state);   // guards against cycles
        SitePinInst src = net.getSource();
        if (src == null) return state;
        Cell driver = null;
        for (Cell c : DesignTools.getConnectedCells(src)) { driver = c; break; }
        if (driver == null && src.getSiteInst() != null && src.getSiteInst().getDesign() != null) {
            // e.g. an input buffer in an IO site: take the logical net's source cell
            com.xilinx.rapidwright.design.Design d = src.getSiteInst().getDesign();
            com.xilinx.rapidwright.edif.EDIFHierNet hnet = d.getNetlist().getHierNetFromName(net.getName());
            if (hnet != null) {
                for (com.xilinx.rapidwright.edif.EDIFHierPortInst p : hnet.getLeafHierPortInsts(true, false)) {
                    Cell c = p.getPhysicalCell(d);
                    if (c != null && c.getSiteInst() == src.getSiteInst()) { driver = c; break; }
                }
            }
        }
        if (driver == null || driver.getBEL() == null) { rootDescriptions.put(net, "no driver cell found for " + src); return state; }
        String outPhys = null;
        for (String phys : driver.getPinMappingsP2L().keySet()) {
            BELPin bp = driver.getBEL().getPin(phys);
            if (bp != null && bp.isOutput()) { outPhys = phys; break; }
        }
        if (outPhys == null) return state;
        // input pin with an arc to the output (the clock input of a buffer, the pad of an input buffer)
        String inPhys = null;
        float[] arc = null;
        short[] belIdx = new short[dm.getCornerCount()];
        for (int i = 0; i < belIdx.length; i++) {
            try { belIdx[i] = dm.getDelayModel(i).getBELIndex(driver.getBELName()); } catch (RuntimeException e) { belIdx[i] = -1; }
        }
        if (belIdx[0] < 0) return state;
        // candidate inputs: the cell's mapped input pins, then (for pads) every input pin of the BEL
        List<String> candidates = new ArrayList<>();
        for (String phys : driver.getPinMappingsP2L().keySet()) {
            BELPin bp = driver.getBEL().getPin(phys);
            if (bp != null && bp.isInput()) candidates.add(phys);
        }
        for (BELPin bp : driver.getBEL().getPins()) if (!bp.getName().equals(outPhys) && !candidates.contains(bp.getName())) candidates.add(bp.getName());
        for (String phys : candidates) {
            short d0 = dm.getDelayModel(0).getLogicDelay(belIdx[0], phys, outPhys);
            if (d0 < 0) continue;
            if (arc == null || d0 > arc[0]) {
                arc = new float[4];
                for (int i = 0; i < 4 && i < belIdx.length; i++) {
                    short di = belIdx[i] < 0 ? -1 : dm.getDelayModel(i).getLogicDelay(belIdx[i], phys, outPhys);
                    arc[i] = di < 0 ? d0 : di;
                }
                inPhys = phys;
            }
        }
        if (arc == null) { rootDescriptions.put(net, "no input arc on " + driver + " (" + driver.getBELName() + ") to " + outPhys); return state; }
        String logicalIn = driver.getLogicalPinMapping(inPhys);
        SitePinInst inPin = logicalIn == null ? null : driver.getSitePinFromLogicalPin(logicalIn, null);
        Net upstream = inPin == null ? null : inPin.getNet();
        if (upstream != null && isGlobalClockNet(upstream)) {
            ClockTree ut = analyze(upstream, dm);
            Node n = inPin.getConnectedNode();
            float[] us = n == null ? null : ut.leafState.get(n);
            if (us != null) System.arraycopy(us, 0, state, 0, 8);
            float[] site = siteDelay(inPin, driver);
            for (int i = 0; i < 4; i++) state[i] += site[i];
        } else if (upstream != null && !upstream.getPIPs().isEmpty()) {
            float[] up = rootState(upstream, dm);
            System.arraycopy(up, 0, state, 0, 8);
            VersalTimingModel.SinkDelay sd = dm.calcNetDelays(upstream).get(inPin);
            if (sd != null && sd.routed) for (int i = 0; i < 4; i++) state[i] += sd.getTotal(Math.min(i, sd.interconnect.length - 1));
        }
        int missesBefore = dm.getIntraSiteMissCount();
        float[] drv = dm.driverIntraSiteDelays(src, net);
        if (dm.getIntraSiteMissCount() > missesBefore) drv = new float[4];   // unknown buffer output: Vivado charges nothing there
        float[] before = state.clone();
        for (int i = 0; i < 4; i++) state[i] += arc[i] + drv[Math.min(i, drv.length - 1)];
        rootDescriptions.put(net, String.format("driver %s (%s) %s->%s: upstream %s = %.0f/%.0f ps, arc %.0f/%.0f, out intra %.0f/%.0f, root %.0f/%.0f (slow max/min)",
                driver, driver.getBELName(), inPhys, outPhys, upstream == null ? "port" : upstream.getName(), before[0], before[1], arc[0], arc[1], drv[0], drv[1], state[0], state[1]));
        return state;
    }

    /** Builds the route tree of a clock net and prices it from a zero root (the net's own delay). */
    public ClockTree analyze(Net net) {
        return analyze(net, null);
    }

    /**
     * Builds the route tree of a clock net and prices it; with a data-path model the root starts at
     * the clock's arrival from the port through the input and global buffers ({@link #rootState}).
     */
    public ClockTree analyze(Net net, VersalTimingModel dm) {
        if (currentDesign == null) for (SitePinInst p : net.getPins()) if (p.getSiteInst() != null) { currentDesign = p.getSiteInst().getDesign(); break; }
        ClockTree t = new ClockTree(net);
        float[] root = rootState(net, dm);
        Set<Node> ends = new HashSet<>();
        for (PIP pip : net.getPIPs()) {
            Node s = pip.getStartNode(), e = pip.getEndNode();
            if (s == null || e == null) continue;
            if (pip.isBidirectional() && pip.isReversed()) { Node x = s; s = e; e = x; }
            t.parent.put(e, s);
            t.parentPip.put(e, pip);
            t.children.computeIfAbsent(s, k -> new ArrayList<>(2)).add(e);
            t.childPips.computeIfAbsent(s, k -> new ArrayList<>(2)).add(pip);
            ends.add(e);
        }
        // sinks the PIP list does not reach (e.g. DSP58 CLK pins in checkpoints assembled outside Vivado:
        // Vivado treats the hops from the tile's clock wire into the site pin as implicit): search uphill
        // (bounded breadth-first) for a node of the tree and add the hops of that path
        for (SitePinInst spi : net.getSinkPins()) {
            Node sn = spi.getConnectedNode();
            if (sn == null || t.parent.containsKey(sn) || t.children.containsKey(sn)) continue;
            Map<Node, PIP> via = new HashMap<>();   // node -> the pip that reached it from below (its downhill side)
            Deque<Node> q = new ArrayDeque<>();
            q.add(sn); via.put(sn, null);
            Node hit = null;
            int visited = 0;
            while (!q.isEmpty() && hit == null && visited < 4000) {
                Node cur = q.poll(); visited++;
                // among the uphill pips, prefer one whose (tree node, its incoming pip, this pip) context is in
                // the exact table: that is the hop Vivado's own trace recorded for this design
                List<PIP> ups = new ArrayList<>(cur.getAllUphillPIPs());
                ups.sort((p1, p2) -> Integer.compare(implicitRank(t, p2), implicitRank(t, p1)));
                for (PIP p : ups) {
                    Node up = p.getStartNode();
                    if (up == null || via.containsKey(up)) continue;
                    String w = up.getWireName();
                    if (w.startsWith("VCC") || w.startsWith("GND")) continue;
                    via.put(up, p);
                    if (t.children.containsKey(up) || t.parent.containsKey(up)) { hit = up; break; }
                    if (via.size() < 4000) q.add(up);
                }
            }
            if (hit == null) continue;
            for (PIP pip = via.get(hit); pip != null; pip = via.get(pip.getEndNode())) {
                Node ps = pip.getStartNode(), pe = pip.getEndNode();
                if (!t.parent.containsKey(pe)) {
                    t.parent.put(pe, ps);
                    t.parentPip.put(pe, pip);
                    t.children.computeIfAbsent(ps, k -> new ArrayList<>(2)).add(pe);
                    t.childPips.computeIfAbsent(ps, k -> new ArrayList<>(2)).add(pip);
                    ends.add(pe);
                    t.implicitNodes.add(pe);
                    t.implicitHops++;
                }
            }
        }
        // Vivado's trace gives no step to a node entered through an unbuffered PIP ("->", e.g. INT_NODE_IMUX_ATOM_*
        // behind a BNODE when a clock is routed over interconnect): the driver's context uses that node's outgoing
        // PIP and its children count toward the driver's fanout. Merge such nodes into their driver.
        for (Node e : new ArrayList<>(t.parentPip.keySet())) {
            PIP pin = t.parentPip.get(e);
            List<Node> kids = t.children.get(e);
            if (kids == null || kids.isEmpty() || isBuffered(pin)) continue;
            Node s = t.parent.get(e);
            List<Node> sk = t.children.get(s);
            List<PIP> sp = t.childPips.get(s);
            int i = sk.indexOf(e);
            if (i < 0) continue;
            sk.remove(i);
            sp.remove(i);
            List<PIP> kp = t.childPips.get(e);
            for (int j = 0; j < kids.size(); j++) { sk.add(kids.get(j)); sp.add(kp.get(j)); t.parent.put(kids.get(j), s); }
            t.children.remove(e);
            t.childPips.remove(e);
            t.parent.remove(e);
            t.parentPip.remove(e);
            t.merged++;
        }
        for (Node s : t.children.keySet()) if (!ends.contains(s)) t.roots.add(s);
        // sink pins whose node is a root of its own (unrouted) are ignored
        Deque<Node> stack = new ArrayDeque<>(t.roots);
        Map<Node, float[]> arrivingState = t.arriving;   // state of the parent toward this node
        for (Node r : t.roots) arrivingState.put(r, expand(root));
        while (!stack.isEmpty()) {
            Node n = stack.pop();
            float[] in = arrivingState.get(n);
            PIP inPip = t.parentPip.get(n);
            List<Node> kids = t.children.get(n);
            List<Node> siblings = inPip == null ? null : t.children.get(inPip.getStartNode());
            int parentFanout = siblings == null ? 1 : siblings.size();
            if (kids == null || kids.isEmpty()) {
                lastTier = "";
                float[] step = step(n, inPip, null, 0, parentFanout);
                if (stepDump != null) stepDump.println(net.getName() + "," + n + "," + pipName(inPip) + ",," + 0 + "," + parentFanout + "," + lastTier + "," + step[0] + "," + step[1] + "," + step[2] + "," + step[3]);
                t.leafState.put(n, add(in, step));
                continue;
            }
            List<PIP> pips = t.childPips.get(n);
            Map<Node, float[]> toward = new HashMap<>();
            for (int i = 0; i < kids.size(); i++) {
                lastTier = "";
                float[] step = step(n, inPip, pips.get(i), kids.size(), parentFanout);
                if (stepDump != null) stepDump.println(net.getName() + "," + n + "," + pipName(inPip) + "," + pipName(pips.get(i)) + "," + kids.size() + "," + parentFanout + "," + lastTier + "," + step[0] + "," + step[1] + "," + step[2] + "," + step[3]);
                float[] s = add(in, step);
                toward.put(kids.get(i), s);
                arrivingState.put(kids.get(i), s);
                stack.push(kids.get(i));
            }
            t.stateToward.put(n, toward);
        }
        for (SitePinInst spi : net.getSinkPins()) {
            Node n = spi.getConnectedNode();
            float[] s = n == null ? null : t.leafState.get(n);
            if (s == null && n != null && t.stateToward.containsKey(n)) s = t.stateToward.get(n).values().iterator().next();
            if (s == null) continue;
            t.sinkArrival.put(spi, ClockTree.arrival(s));
        }
        return t;
    }

    /** Arrival (4 corners) at a cell's clock pin fed by the given sink site pin: tree arrival plus intra-site delay. */
    public float[] pinArrival(ClockTree t, SitePinInst spi, Cell cell) {
        float[] a = t.sinkArrival.get(spi);
        if (a == null) return null;
        float[] site = siteDelay(spi, cell);
        float[] pin = new float[4];
        for (int i = 0; i < 4; i++) pin[i] = a[i] + site[i];
        if (isImplicitSink(t, spi)) {
            float[] extra = clkImplicit.get(spi.getSiteTypeEnum().name() + "\t" + spi.getName());
            if (extra != null) for (int i = 0; i < 4; i++) pin[i] += extra[i];
        }
        return pin;
    }

    /** Whether the sink's node was attached to the tree by implicit hops (no PIPs into it in the checkpoint). */
    public static boolean isImplicitSink(ClockTree t, SitePinInst spi) {
        Node n = spi.getConnectedNode();
        return n != null && t.implicitNodes.contains(n);
    }

    /** state + step: the step's 8 moments go to the totals and to the step's bucket. */
    static float[] add(float[] a, float[] step) {
        float[] s = new float[STATE_SIZE];
        System.arraycopy(a, 0, s, 0, Math.min(a.length, STATE_SIZE));
        int b = step.length > 8 ? (int) step[8] : 0;
        for (int i = 0; i < 8; i++) {
            s[i] += step[i];
            s[8 + 8 * b + i] += step[i];
        }
        return s;
    }

    String lastTier = "";
    /** STEPS_CSV=<file>: every context of every analyzed tree with its tier and step moments (slow max/min, fast max/min). */
    private static final java.io.PrintWriter stepDump = openStepDump();
    private static java.io.PrintWriter openStepDump() {
        String f = System.getenv("STEPS_CSV");
        if (f == null) return null;
        try {
            java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(f), true);
            pw.println("net,node,incoming_pip,outgoing_pip,fanout,parent_fanout,tier,slow_max,slow_min,fast_max,fast_min");
            return pw;
        } catch (IOException e) { throw new RuntimeException(e); }
    }
    private void tier(String name) {
        lastTier = name;
        tierUse.merge(name, 1, Integer::sum);
    }

    /** Step moments of a node in its context, from the exact table or the fallbacks, at the node's fanout
     *  (and its parent's fanout, for the hop into the slice clock pin). */
    static boolean isBuffered(PIP p) {
        com.xilinx.rapidwright.device.PIPType ty = p.getPIPType();
        return ty != com.xilinx.rapidwright.device.PIPType.DIRECTIONAL_NOT_BUFFERED21 && ty != com.xilinx.rapidwright.device.PIPType.BI_DIRECTIONAL_NOT_BUFFERED20;
    }

    float[] step(Node n, PIP in, PIP out, int fanout, int parentFanout) {
        float[] m = baseStep(n, in, out, fanout);
        String wire = n.getWireName();
        if (!leafDly.isEmpty() && wire.endsWith("_O_PIN") && wire.startsWith("CLK_LEAF_SITES_")) {
            // the programmed leaf delay (BUFDIV_LCLK_DELAY CONFIG.DELAY VAL0/VAL4/VAL8) is carried by the
            // checkpoint on the leaf's route-through PIP as its delay index (0/4/8); the side file is the fallback
            String lvl = in != null && in.isRouteThru() && in.getDelayIndex() > 0 ? "VAL" + in.getDelayIndex() : leafLevel(n);
            float[] off = leafDly.get(lvl);
            if (off != null) { m = m.clone(); for (int i = 0; i < 4; i++) m[i] += off[i]; tier("leafdly"); }
        }
        if (fanout > 1) for (Map.Entry<String, float[]> e : fanScale.entrySet()) if (wire.startsWith(e.getKey())) return scaled(m, e.getValue(), fanout);
        if (parentFanout > 1) for (Map.Entry<String, float[]> e : fanScaleParent.entrySet()) if (wire.startsWith(e.getKey())) return scaled(m, e.getValue(), parentFanout);
        return m;
    }

    private static float[] scaled(float[] m, float[] c, int k) {
        float[] s = m.clone();
        for (int i = 0; i < 4; i++) {
            float f = 1 + c[i] * (k - 1);
            s[i] = m[i] * f;
            s[4 + i] = m[4 + i] * f * f;
        }
        return s;
    }

    /** The table step (fanout-1 form) of a node in its context, from the exact table or the fallbacks. */
    float[] baseStep(Node n, PIP in, PIP out, int fanout) {
        String node = n.toString();
        float[] m = ctx.get(node + "\t" + pipName(in) + "\t" + pipName(out));
        if (m == null && (in != null && in.isBidirectional() || out != null && out.isBidirectional())) {
            // Vivado prints a bidirectional pip the same way whichever way it is used; RapidWright's name follows the use
            m = ctx.get(node + "\t" + flipped(in) + "\t" + flipped(out));
            if (m == null) m = ctx.get(node + "\t" + flipped(in) + "\t" + pipName(out));
            if (m == null) m = ctx.get(node + "\t" + pipName(in) + "\t" + flipped(out));
        }
        if (m != null) { tier("ctx"); return m; }
        if (!tctx.isEmpty()) {
            String tk = trackFreeKey(n, in, out);
            if (tk != null) {
                m = tctx.get(tk);
                if (m != null) { tier("tctx"); return m; }
            }
        }
        if (!fam.isEmpty() || !famGeo.isEmpty()) {
            String fk = famKey(n) + "\t" + famPip(in) + "\t" + famPip(out);
            String t = famTier(n);
            if (!t.isEmpty() && out != null) {
                int off = t.equals("dy") ? out.getTile().getTileYCoordinate() - n.getTile().getTileYCoordinate()
                                         : out.getTile().getTileXCoordinate() - n.getTile().getTileXCoordinate();
                m = famGeo.get("FAM" + t.toUpperCase() + "\t" + fk + "\t" + off);
                if (m != null) { tier("fam" + t); return m; }
            }
            m = fam.get(fk);
            if (m != null) { tier("fam"); return m; }
        }
        String nk = nodeKey(n), ik = pipKey(in), ok = pipKey(out);
        m = cls.get(nk + "\t" + ik + "\t" + ok + "\t" + fanout);
        if (m != null) { tier("class"); return m; }
        m = cls2.get(nk + "\t" + ik + "\t" + ok);
        if (m != null) { tier("class2"); return m; }
        IntentCode ic = n.getIntentCode();
        m = cls3.get(ic == null ? "-" : ic.name());
        if (DEBUG_TIERS && debugShown++ < DEBUG_LIMIT) System.out.println("[clock tier " + (m != null ? "intent" : "none") + "] " + node + " | " + pipName(in) + " | " + pipName(out) + " fanout " + fanout);
        if (m != null) { tier("intent"); return m; }
        tier("none");
        return new float[9];
    }

    private static String flipped(PIP p) {
        if (p == null) return "";
        String s = p.toString();
        int dot = s.indexOf('.', s.indexOf('/'));
        String[] w = s.substring(dot + 1).split("<<->>");
        return w.length == 2 ? s.substring(0, dot + 1) + w[1] + "<<->>" + w[0] : s;
    }

    /** The slice's leaf clock deskew setting: {mode, taps} from the FF_CLK_MOD BEL attributes ("" / -1 if none). */
    public static Object[] leafClockDelay(Cell cell) {
        if (cell == null || cell.getSiteInst() == null || cell.getSiteInst().getDesign() == null) return new Object[] {"", -1};
        Map<com.xilinx.rapidwright.device.Site, SiteConfig> attrs = cell.getSiteInst().getDesign().getBELAttrs();
        SiteConfig sc = attrs == null ? null : attrs.get(cell.getSite());
        com.xilinx.rapidwright.device.BEL mod = cell.getSite().getBEL("FF_CLK_MOD");
        if (sc == null || mod == null) return new Object[] {"", -1};
        BELAttr m = sc.getBELAttribute(mod, "IMUX_CLK_MODE");
        BELAttr t = sc.getBELAttribute(mod, "CLK_DLY_VAL");
        String mode = m == null ? "" : m.getValue();
        int taps = -1;
        if (t != null) {
            String v = t.getValue();
            int q = v.indexOf("'h");
            try { taps = q >= 0 ? Integer.parseInt(v.substring(q + 2), 16) : Integer.parseInt(v); } catch (NumberFormatException e) { taps = -1; }
        }
        return new Object[] {mode, taps};
    }

    /**
     * Intra-site clock delay from the sink site pin to the clocked cell's clock pin (4 corners):
     * the base term of the (site type, site pin, BEL pin) plus the slice's programmed leaf delay
     * (IMUX_CLK_MODE / CLK_DLY_VAL taps) when present.
     */
    public float[] siteDelay(SitePinInst spi, Cell cell) {
        SiteInst si = spi.getSiteInst();
        if (si != null && cell != null && cell.getBEL() != null) {
            // clock pins first, then any other mapped input pin (e.g. the I pin of a clock buffer)
            List<String> pins = new ArrayList<>();
            for (String phys : cell.getPinMappingsP2L().keySet()) if (VersalTimingGraph.isClockPin(phys)) pins.add(phys);
            for (String phys : cell.getPinMappingsP2L().keySet()) {
                BELPin bp = cell.getBEL().getPin(phys);
                if (bp != null && bp.isInput() && !pins.contains(phys)) pins.add(phys);
            }
            for (String phys : pins) {
                String bp = cell.getBELName() + "/" + phys;
                float[] d = clkSite.get(si.getSiteTypeEnum().name() + "\t" + spi.getName() + "\t" + bp);
                if (d == null) d = clkSite.get(si.getSiteTypeEnum().name() + "\t" + spi.getName() + "\t" + VersalTimingModel.toLetterA(bp));
                if (d == null) continue;
                Object[] leaf = leafClockDelay(cell);
                String mode = (String) leaf[0];
                int taps = (Integer) leaf[1];
                if (mode.isEmpty() || taps < 0) return d;
                float[] tap = clkTap.get(mode + "\t" + taps);
                if (tap == null) {
                    // unseen tap count: extrapolate from the two largest known counts of this mode
                    int best = -1, second = -1;
                    for (String k : clkTap.keySet()) if (k.startsWith(mode + "\t")) { int n = Integer.parseInt(k.substring(mode.length() + 1)); if (n > best) { second = best; best = n; } else if (n > second) second = n; }
                    if (best >= 0) {
                        float[] a = clkTap.get(mode + "\t" + best), b = second >= 0 ? clkTap.get(mode + "\t" + second) : null;
                        tap = new float[4];
                        for (int i = 0; i < 4; i++) tap[i] = b == null ? a[i] : a[i] + (a[i] - b[i]) / (best - second) * (taps - best);
                    }
                }
                float[] out = d.clone();
                if (tap != null) for (int i = 0; i < 4; i++) out[i] += tap[i];
                return out;
            }
        }
        if (si != null) {
            // hard blocks (DSP58, BRAM): one clock site pin feeds several internal BELs and the table may only
            // name one of them; any entry for this site type and site pin is the same site-pin-to-clock delay
            String prefix = si.getSiteTypeEnum().name() + "\t" + spi.getName() + "\t";
            for (Map.Entry<String, float[]> e : clkSite.entrySet()) if (e.getKey().startsWith(prefix)) return e.getValue();
        }
        siteMisses++;
        return new float[4];
    }

    /**
     * Clock pessimism removal between two sinks: the corner spread of the shared clock path, i.e.
     * arrival(max corner) - arrival(min corner) at the last node both paths share, for the slow and
     * the fast process ({slow, fast}). Zero if the sinks share no node (different roots).
     */
    /**
     * Clock pessimism removal the way Vivado computes it (setup: {slow, fast}; the same magnitudes
     * apply to hold): the max-min spread of the state arriving at the nearest common node of the two
     * sink branches (this includes the root chain), minus the VT-drift skew when positive.
     */
    public float[] pessimism(ClockTree t, SitePinInst launch, SitePinInst capture) {
        return pessimism(t, launch, capture, 5, false);
    }

    /** Pessimism removal for the hold analysis (launch at the min corner, capture at the max corner). */
    public float[] holdPessimism(ClockTree t, SitePinInst launch, SitePinInst capture) {
        return pessimism(t, launch, capture, 5, true);
    }

    public float[] pessimism(ClockTree t, SitePinInst launch, SitePinInst capture, int variant) {
        return pessimism(t, launch, capture, variant, false);
    }

    /** A node's own wire delay (4 corners): exact class, then intent, else 0. */
    public float[] wireDelay(Node n) {
        float[] w = wire.get(nodeKey(n));
        if (w == null) w = wire2.get(n.getWireName().replaceAll("\\d+", ""));
        return w == null ? new float[4] : w;
    }

    /** 2 when the exact table knows the context (start node, its incoming pip, this pip), 1 when the start node is in the tree, else 0. */
    private int implicitRank(ClockTree t, PIP p) {
        Node up = p.getStartNode();
        if (up == null) return 0;
        boolean inTree = t.children.containsKey(up) || t.parent.containsKey(up);
        if (!inTree) return 0;
        PIP in = t.parentPip.get(up);
        return ctx.containsKey(up.toString() + "\t" + pipName(in) + "\t" + pipName(p)) ? 2 : 1;
    }

    /** The state of the programmable delay element (CLKE2_PD_OPT_DELAY *_I_PIN) a sink branch passes through below the given node, or null. */
    private float[] delayElementState(ClockTree t, Node sink, Node stopAt) {
        for (Node x = sink; x != null && !x.equals(stopAt); x = t.parent.get(x)) {
            String w = x.getWireName();
            if (w != null && w.contains("PD_OPT_DELAY") && w.endsWith("_I_PIN")) {
                Map<Node, float[]> tw = t.stateToward.get(x);
                if (tw != null && !tw.isEmpty()) return tw.values().iterator().next();
                return t.leafState.get(x);
            }
        }
        return null;
    }

    /**
     * Vivado's VT-drift skew between two sink branches for the given corner index: the launch and
     * capture paths' delay elements see different X / Y / local delay mixes and drift differently.
     * 0 when both branches share the element (the common node is at or below it) or one has none.
     */
    public float vtSkew(ClockTree t, Node launch, Node capture, Node common, int corner) {
        float[] l = delayElementState(t, launch, common), c = delayElementState(t, capture, common);
        if (l == null || c == null) return 0;
        float skew = 0;
        float sign = corner % 2 == 0 ? 1 : -1;
        for (int b = 0; b < BUCKETS.length; b++) {
            float vl = l[8 + 8 * b + corner] + sign * (float) Math.sqrt(Math.max(0, l[8 + 8 * b + 4 + corner]));
            float vc = c[8 + 8 * b + corner] + sign * (float) Math.sqrt(Math.max(0, c[8 + 8 * b + 4 + corner]));
            // coefficient order in the table: X, Y, Local; bucket order: Local, GlobalX, GlobalY
            float f = b == 0 ? vtDrift[corner][2] : b == 1 ? vtDrift[corner][0] : vtDrift[corner][1];
            skew += f * (vl - vc);
        }
        return skew;
    }

    /**
     * @param variant 0: state of the divergence node toward the launch branch; 1: toward the capture
     *                branch; 2: the state arriving at the divergence node (before its own step)
     */
    public float[] pessimism(ClockTree t, SitePinInst launch, SitePinInst capture, int variant, boolean hold) {
        Node a = launch.getConnectedNode(), b = capture.getConnectedNode();
        if (a == null || b == null) return new float[2];
        List<Node> pa = t.pathToRoot(a), pb = t.pathToRoot(b);
        Set<Node> onB = new HashSet<>(pb);
        Node common = null, towardLaunch = null, towardCapture = null;
        for (Node x : pa) {
            if (onB.contains(x)) { common = x; break; }
            towardLaunch = x;
        }
        if (common == null) return new float[2];
        for (Node x : pb) { if (x.equals(common)) break; towardCapture = x; }
        float[] arr;
        if (variant == 5) {
            // Vivado (sta.RSSCRPR): with the state S arriving at the nearest common node, the node's own
            // wire w (means only), and the leaf states L (launch corner) and C (capture corner):
            //   credit = (S.mu_max + w_max) - (S.mu_min + w_min) + sqrt(L.V) + sqrt(C.V) - sqrt(L.V - S.V + C.V - S.V)
            // i.e. the raw max-min spread minus the RSS of the two uncommon segments, then minus the
            // VT-drift skew of the two delay elements when positive.
            float[] s0 = t.arriving.get(common);
            float[] sl = t.leafState.get(a), sc = t.leafState.get(b);
            if (sl == null && t.stateToward.containsKey(a)) sl = t.stateToward.get(a).values().iterator().next();
            if (sc == null && t.stateToward.containsKey(b)) sc = t.stateToward.get(b).values().iterator().next();
            if (s0 == null || sl == null || sc == null) return new float[2];
            float[] w = wireDelay(common);
            float[] out = new float[2];
            for (int k = 0; k < 2; k++) {
                int iMax = 2 * k, iMin = 2 * k + 1;
                int iL = hold ? iMin : iMax, iC = hold ? iMax : iMin;
                double vL = Math.max(0, sl[4 + iL]), vC = Math.max(0, sc[4 + iC]);
                double vu = Math.max(0, vL - s0[4 + iL]) + Math.max(0, vC - s0[4 + iC]);
                double credit = (s0[iMax] + w[iMax]) - (s0[iMin] + w[iMin]) + Math.sqrt(vL) + Math.sqrt(vC) - Math.sqrt(vu);
                float vt = Math.max(0, vtSkew(t, a, b, common, iL));
                out[k] = (float) credit - vt;
            }
            return out;
        } else if (variant == 4) {
            // means only: the common part's max-min mean difference, no variance contribution
            float[] sCommon = t.arriving.get(common);
            if (sCommon == null) return new float[2];
            return new float[] {sCommon[0] - sCommon[1], sCommon[2] - sCommon[3]};
        } else if (variant == 3) {
            // Vivado-style removal under RSS accumulation: the pessimism is the difference between the
            // launch/capture arrivals and what the uncommon segments contribute on their own, i.e.
            // sqrt(V_launch) + sqrt(V_capture) - sqrt(V_launch - V_common) - sqrt(V_capture - V_common)
            float[] sl = t.leafState.get(a), sc = t.leafState.get(b), sCommon = t.arriving.get(common);
            if (sl == null && t.stateToward.containsKey(a)) sl = t.stateToward.get(a).values().iterator().next();
            if (sc == null && t.stateToward.containsKey(b)) sc = t.stateToward.get(b).values().iterator().next();
            if (sl == null || sc == null || sCommon == null) return new float[2];
            float[] out = new float[2];
            for (int k = 0; k < 2; k++) {
                int iMax = 2 * k, iMin = 2 * k + 1;
                double vl = Math.max(0, sl[4 + iMax]), vc = Math.max(0, sc[4 + iMin]);
                double vul = Math.max(0, vl - sCommon[4 + iMax]), vuc = Math.max(0, vc - sCommon[4 + iMin]);
                out[k] = (float) (Math.sqrt(vl) + Math.sqrt(vc) - Math.sqrt(vul) - Math.sqrt(vuc));
            }
            return out;
        } else if (variant == 2) {
            Node parent = t.parent.get(common);
            arr = parent == null ? new float[4] : t.arrivalAt(parent, common);
        } else if (variant == 1) {
            arr = t.arrivalAt(common, common.equals(b) ? null : towardCapture);
        } else {
            arr = t.arrivalAt(common, common.equals(a) ? null : towardLaunch);
        }
        if (arr == null) return new float[2];
        return new float[] {arr[0] - arr[1], arr[2] - arr[3]};
    }

    /** VT-drift coefficients (fX, fY, fLocal) of a corner index. */
    public float[] getVtDrift(int corner) {
        return vtDrift[corner].clone();
    }

    /** Wire delay fallback by wire-name family (digits removed), or null. */
    public float[] wireDelayByFamily(String wireName) {
        return wire2.get(wireName.replaceAll("\\d+", ""));
    }

    public Map<String, Integer> getTierUse() {
        return tierUse;
    }

    public int getSiteMissCount() {
        return siteMisses;
    }

    public int getContextCount() {
        return ctx.size();
    }

    /** Whether a net is a routed global clock net (uses the global clock network). */
    public static boolean isGlobalClockNet(Net net) {
        if (net.getPIPs().isEmpty()) return false;
        if (net.isClockNet()) return true;
        for (PIP p : net.getPIPs()) {
            Node e = p.getEndNode();
            if (e != null && e.getIntentCode() != null && e.getIntentCode().name().startsWith("NODE_GLOBAL")) return true;
        }
        return false;
    }
}
