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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.timing.DelayModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A data-path timing graph for a placed and routed Versal design, built from a
 * {@link VersalTimingModel} (net delays) and the Versal {@link DelayModel}s (logic delays), for
 * every corner the model holds at once.
 *
 * <p>Vertices are physical cell pins (cell + BEL pin). Logic arcs connect input to output pins
 * of one cell; net edges connect a driver pin to each sink pin with the modelled net delay.
 * Sequential cells launch at their clock-to-output arcs; their data/control inputs with a setup
 * or hold arc from the clock are endpoints. Max-delay corners propagate the longest arrival,
 * min-delay corners the shortest; the arrival at an endpoint corresponds to Vivado's "Data Path
 * Delay" of the setup or hold analysis at that corner (clock network and skew are not modelled).
 */
public class VersalTimingGraph {

    public static class Vertex {
        public final Cell cell;
        public final String pin;      // BEL pin name (physical pin)
        /** arrival per corner: -inf (max corners) / +inf (min corners) until reached */
        public final float[] arrival;
        /** critical predecessor edge per corner */
        public final Edge[] pred;
        public final List<Edge> outs = new ArrayList<>(2);
        public boolean launch;        // clock-to-output pin of a sequential cell
        public boolean endpoint;      // data/control input of a sequential cell
        /** timing check per corner: setup requirement at max corners, hold requirement at min corners (ps) */
        public final float[] check;
        /** arrivals per launch clock group (see {@link Tagged}); null until reached */
        public List<Tagged> tags;

        Vertex(Cell cell, String pin, int corners) {
            this.cell = cell;
            this.pin = pin;
            arrival = new float[corners];
            pred = new Edge[corners];
            check = new float[corners];
        }

        public String getName() {
            return cell.getName() + "/" + pin;
        }

        @Override
        public String toString() {
            return getName();
        }
    }

    /**
     * The extreme arrival at a vertex among the launches of one clock group, with its critical
     * predecessor per corner. The group ("tag") is chosen by the caller of
     * {@link #seedLaunch(Vertex, float[], Object)}: the slack analysis uses the launch's clock-tree
     * leaf node, because clock pessimism removal depends on where the launch and capture clock paths
     * meet, so the launch with the extreme arrival is not always the one with the worst slack. A
     * launch seeded without a tag is in the default group (tag null); with all launches in one group
     * the tagged arrivals equal {@link Vertex#arrival}.
     */
    public static class Tagged {
        public final Object tag;
        public final float[] arrival;
        public final Edge[] pred;

        Tagged(Object tag, int corners) {
            this.tag = tag;
            arrival = new float[corners];
            pred = new Edge[corners];
        }
    }

    public static class Edge {
        public final Vertex src, dst;
        /** delay per corner (ps) */
        public final float[] delay;
        public final Net net;          // null for logic arcs
        public final String kind;      // "logic", "net", "intrasite"
        /** "net" edges: the sink site pin the edge is routed to, and the intra-site part of the delay
         *  (driver BEL pin -> site pin plus site pin -> sink BEL pin, per corner); null otherwise */
        public final SitePinInst sinkPin;
        public final float[] intraSite;

        Edge(Vertex src, Vertex dst, float[] delay, Net net, String kind) {
            this(src, dst, delay, net, kind, null, null);
        }

        Edge(Vertex src, Vertex dst, float[] delay, Net net, String kind, SitePinInst sinkPin, float[] intraSite) {
            this.src = src;
            this.dst = dst;
            this.delay = delay;
            this.net = net;
            this.kind = kind;
            this.sinkPin = sinkPin;
            this.intraSite = intraSite;
        }
    }

    private final Design design;
    private final VersalTimingModel model;
    private final int nc;
    private final Map<String, Vertex> vertices = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Vertex> endpoints = new ArrayList<>();
    private int unknownBels = 0, unroutedSinks = 0, netsSkipped = 0, prunedLutArcs = 0;
    private final Set<String> unknownBelNames = new HashSet<>();
    /** clock-to-output delay per launch vertex (per corner), before any clock arrival is added */
    private final Map<Vertex, float[]> clkToQ = new HashMap<>();
    /** physical clock pin of every launch and endpoint vertex's cell */
    private final Map<Vertex, String> clockPin = new HashMap<>();

    /** Whether a sink whose site pin is not routed still gets a "net" edge (interconnect 0, intra-site terms only). */
    private final boolean edgesForUnroutedSinks;

    public VersalTimingGraph(Design design, VersalTimingModel model) {
        this(design, model, false);
    }

    /**
     * @param edgesForUnroutedSinks when true, a sink reached through a site pin that has no route yet
     *        gets a "net" edge carrying only the intra-site terms (interconnect 0), so that a router can
     *        fill in the route delay later; when false (the report), such a sink has no edge.
     */
    public VersalTimingGraph(Design design, VersalTimingModel model, boolean edgesForUnroutedSinks) {
        this.design = design;
        this.model = model;
        this.nc = model.getCornerCount();
        this.edgesForUnroutedSinks = edgesForUnroutedSinks;
    }

    public VersalTimingModel getModel() {
        return model;
    }

    public int getCornerCount() {
        return nc;
    }

    /** Whether the primary corner is a max-delay corner. */
    public boolean isMaxDelay() {
        return model.isMax(0);
    }

    private float unset(int i) {
        return model.isMax(i) ? Float.NEGATIVE_INFINITY : Float.POSITIVE_INFINITY;
    }

    private static boolean isSet(float a) {
        return !Float.isInfinite(a);
    }

    private boolean better(int i, float a, float current) {
        return model.isMax(i) ? a > current : a < current;
    }

    public Vertex getVertex(Cell c, String pin) {
        return vertices.get(c.getName() + "/" + pin);
    }

    private Vertex vertex(Cell c, String pin) {
        return vertices.computeIfAbsent(c.getName() + "/" + pin, k -> {
            Vertex v = new Vertex(c, pin, nc);
            for (int i = 0; i < nc; i++) v.arrival[i] = unset(i);
            return v;
        });
    }

    private void addEdge(Vertex s, Vertex d, float[] delay, Net net, String kind) {
        addEdge(s, d, delay, net, kind, null, null);
    }

    private void addEdge(Vertex s, Vertex d, float[] delay, Net net, String kind, SitePinInst sinkPin, float[] intraSite) {
        Edge e = new Edge(s, d, delay, net, kind, sinkPin, intraSite);
        s.outs.add(e);
        edges.add(e);
    }

    public static boolean isFlipFlop(Cell c) {
        String t = c.getType();
        return t != null && (t.startsWith("FD") || t.startsWith("LD"));
    }

    /**
     * Name of the delay-model BEL section for a cell: the BEL name, suffixed with the cell family
     * (SRL, LUTCY, RAM) for cells that share LUT BELs but have their own arcs. Must match
     * cell_family() in fit_versal_model.py.
     */
    public static String belKeyFor(Cell c) {
        String t = c.getType() == null ? "" : c.getType();
        String fam = t.startsWith("SRL") ? "SRL" : (t.startsWith("LUTCY") || t.startsWith("LUT6CY")) ? "LUTCY"
                : t.startsWith("RAM") ? "RAM" : "";
        return fam.isEmpty() ? c.getBELName() : c.getBELName() + "_" + fam;
    }

    /** Whether a BEL input pin is a clock pin (CLK, CLKARDCLK, CLKBWRCLK, ...). */
    static boolean isClockPin(String belPin) {
        return belPin.equals("CLK") || belPin.startsWith("CLK") || belPin.endsWith("CLK");
    }

    /**
     * INIT value of a LUT cell, or -1 if it cannot be parsed (no pruning then). The LUTCY1/LUTCY2 leaves
     * of a LUT6CY macro and the LUT6/LUT5 leaves of a LUT6_2 macro carry a placeholder INIT; theirs come
     * from the macro's INIT (LUT6CY: low / high 32 bits; LUT6_2: all 64 bits / the low 32).
     */
    static long lutInitValue(Cell c) {
        String type = c.getType();
        if (type != null && (type.equals("LUT6") || type.equals("LUT5"))) {
            // the LUT6 / LUT5 leaves of a LUT6_2 macro carry a placeholder INIT of 0 (which would prune every
            // input): the macro's 64-bit INIT is the LUT6 leaf's, its low 32 bits the LUT5 leaf's
            EDIFHierCellInst h = c.getEDIFHierCellInst();
            EDIFHierCellInst parent = h == null ? null : h.getParent();
            if (parent != null && parent.getInst() != null && "LUT6_2".equals(parent.getCellType().getName())) {
                Long full = parseInit(parent.getInst().getProperty("INIT"));
                if (full == null) return -1;
                return type.equals("LUT5") ? (full & 0xFFFFFFFFL) : (full < 0 ? -1 : full);
            }
        }
        if (type != null && type.startsWith("LUTCY")) {
            EDIFHierCellInst h = c.getEDIFHierCellInst();
            EDIFHierCellInst parent = h == null ? null : h.getParent();
            if (parent != null && parent.getInst() != null && "LUT6CY".equals(parent.getCellType().getName())) {
                Long full = parseInit(parent.getInst().getProperty("INIT"));
                if (full == null) return -1;
                return type.equals("LUTCY1") ? (full & 0xFFFFFFFFL) : (full >>> 32);
            }
        }
        Long v = parseInit(c.getEDIFCellInst() == null ? null : c.getEDIFCellInst().getProperty("INIT"));
        return v == null || v < 0 ? -1 : v;   // a 64-bit INIT with bit 63 set is not pruned (sign); rare
    }

    static Long parseInit(EDIFPropertyValue pv) {
        if (pv == null) return null;
        String v = pv.getValue();
        int q = v.indexOf('\'');
        if (q < 0 || q + 2 > v.length()) return null;
        char radix = Character.toLowerCase(v.charAt(q + 1));
        String digits = v.substring(q + 2).replace("_", "");
        try {
            switch (radix) {
                case 'h': return Long.parseUnsignedLong(digits, 16);
                case 'b': return Long.parseUnsignedLong(digits, 2);
                case 'd': return Long.parseUnsignedLong(digits, 10);
                default: return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Whether the LUT function depends on the logical input mapped to the given physical pin. */
    static boolean lutDependsOn(Cell c, String physPin, long init, int size) {
        String logical = c.getLogicalPinMapping(physPin);
        if (logical == null || !logical.startsWith("I")) return true;
        int k;
        try { k = Integer.parseInt(logical.substring(1)); } catch (NumberFormatException e) { return true; }
        if (size <= 0 || size > 6 || k >= size) return true;
        int n = 1 << size;
        for (int x = 0; x < n; x++) {
            if (((init >>> x) & 1L) != ((init >>> (x ^ (1 << k))) & 1L)) return true;
        }
        return false;
    }

    /** Builds logic arcs and net edges for the whole design. */
    /** Wall time of the build phases: constants, logic arcs, net edges (ms). */
    public final long[] phaseMs = new long[3];

    public void build() {
        long t = System.currentTimeMillis();
        propagateConstants();
        phaseMs[0] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        buildLogicArcs();
        phaseMs[1] = System.currentTimeMillis() - t; t = System.currentTimeMillis();
        buildNetEdges();
        phaseMs[2] = System.currentTimeMillis() - t;
    }

    /** cell name + "/" + physical pin -> constant value (0/1) for pins Vivado would treat as constant. */
    private final Map<String, Integer> constantPins = new HashMap<>();
    private int constantLuts = 0;

    /**
     * Mirrors Vivado's constant propagation through LUTs: pins driven by GND/VCC are constant, a LUT
     * whose function is constant given its constant inputs has a constant output, and that constant
     * flows on through the nets it drives. Arcs from constant pins are not timed by Vivado.
     */
    private void propagateConstants() {
        Map<String, Cell> lutByOutNet = new HashMap<>();       // net name -> LUT cell driving it
        Map<String, List<String>> netSinks = new HashMap<>();  // net name -> sink "cell/physPin"
        for (Net net : design.getNets()) {
            EDIFHierNet hnet = design.getNetlist().getHierNetFromName(net.getName());
            if (hnet == null) continue;
            boolean isConst = net.isStaticNet();
            for (EDIFHierPortInst p : hnet.getLeafHierPortInsts(true, true)) {
                Cell c = p.getPhysicalCell(design);
                if (c == null || c.getBEL() == null) continue;
                String phys = c.getPhysicalPinMapping(p.getPortInst().getName());
                if (phys == null) continue;
                String key = c.getName() + "/" + phys;
                if (p.isOutput()) {
                    if (c.getType() != null && c.getType().startsWith("LUT")) lutByOutNet.put(net.getName(), c);
                } else {
                    netSinks.computeIfAbsent(net.getName(), k -> new ArrayList<>()).add(key);
                    if (isConst) constantPins.put(key, net.isVCCNet() ? 1 : 0);
                }
            }
        }
        boolean changed = true;
        Set<String> constNets = new HashSet<>();
        while (changed) {
            changed = false;
            for (Map.Entry<String, Cell> e : lutByOutNet.entrySet()) {
                if (constNets.contains(e.getKey())) continue;
                Integer v = lutConstantValue(e.getValue());
                if (v == null) continue;
                constNets.add(e.getKey());
                constantLuts++;
                for (String sink : netSinks.getOrDefault(e.getKey(), Collections.emptyList())) {
                    if (constantPins.put(sink, v) == null) changed = true;
                }
            }
        }
    }

    /** Fixed input mask/value of a LUT from its constant input pins: {mask, value}. */
    private int[] fixedInputs(Cell c, int size) {
        int fixedMask = 0, fixedVal = 0;
        for (Map.Entry<String, String> m : c.getPinMappingsP2L().entrySet()) {
            String logical = m.getValue();
            if (logical == null || !logical.startsWith("I")) continue;
            Integer v = constantPins.get(c.getName() + "/" + m.getKey());
            if (v == null) continue;
            int k;
            try { k = Integer.parseInt(logical.substring(1)); } catch (NumberFormatException ex) { continue; }
            if (k >= size) continue;
            fixedMask |= 1 << k;
            if (v != 0) fixedVal |= 1 << k;
        }
        return new int[] {fixedMask, fixedVal};
    }

    /** The constant output value of a LUT given its constant inputs, or null if it is not constant. */
    private Integer lutConstantValue(Cell c) {
        int size = LUTTools.getLUTSize(c);
        long init = lutInitValue(c);
        if (init < 0 || size <= 0 || size > 6) return null;
        int[] fixed = fixedInputs(c, size);
        int n = 1 << size;
        Integer out = null;
        for (int x = 0; x < n; x++) {
            if ((x & fixed[0]) != fixed[1]) continue;
            int b = (int) ((init >>> x) & 1L);
            if (out == null) out = b;
            else if (out != b) return null;
        }
        return out;
    }

    /** Whether the LUT output still depends on the input at physPin once constant inputs are fixed. */
    private boolean lutDependsOnGivenConstants(Cell c, String physPin, long init, int size) {
        String logical = c.getLogicalPinMapping(physPin);
        if (logical == null || !logical.startsWith("I")) return true;
        int k;
        try { k = Integer.parseInt(logical.substring(1)); } catch (NumberFormatException e) { return true; }
        if (size <= 0 || size > 6 || k >= size) return true;
        int[] fixed = fixedInputs(c, size);
        int n = 1 << size;
        for (int x = 0; x < n; x++) {
            if ((x & fixed[0]) != fixed[1]) continue;
            if (((init >>> x) & 1L) != ((init >>> (x ^ (1 << k))) & 1L)) return true;
        }
        return false;
    }

    /** Logic delay per corner of a BEL arc; null if the primary corner has no such arc. */
    /** SmallDelayModel's "no such arc" value; a real setup requirement can be negative (Versal flops: -5..-9 ps) */
    private static final short NO_ARC = -2;
    private static final String DEBUG_CELL = System.getenv("DEBUG_CELL");
    private static final String DEBUG_SINK = System.getenv("DEBUG_SINK");

    private float[] logicDelays(short[] belIdx, String in, String out) {
        short d0 = delayModelAt(0).getLogicDelay(belIdx[0], in, out);
        if (d0 == NO_ARC) return null;
        float[] d = new float[nc];
        d[0] = d0;
        for (int i = 1; i < nc; i++) {
            short di = belIdx[i] < 0 ? NO_ARC : delayModelAt(i).getLogicDelay(belIdx[i], in, out);
            d[i] = di == NO_ARC ? d0 : di;
        }
        return d;
    }

    private DelayModel delayModelAt(int i) {
        return model.getDelayModel(i);
    }

    /** BEL section index per corner for a cell, or null if unknown in the primary corner's model. */
    private short[] belIndices(Cell c) {
        short[] idx = new short[nc];
        for (int i = 0; i < nc; i++) {
            idx[i] = belIndex(delayModelAt(i), c);
            if (i == 0 && idx[0] < 0) return null;
        }
        return idx;
    }

    private static short tryIndex(DelayModel dm, String key) {
        try { return dm.getBELIndex(key); } catch (RuntimeException e) { return -1; }
    }

    /**
     * Index of the BEL section that times a cell, or -1. Sections are written per BEL and site type
     * ("HFF2@SLICEM", the loader's default index map would otherwise merge every flop letter into one
     * entry); older tables have plain names. Lookup order: BEL[_family]@site, BEL@site, BEL[_family],
     * BEL, then the same BEL of another letter (A..H) at this site type, for BELs the training designs
     * only exercised on some letters.
     */
    public static short belIndex(DelayModel dm, Cell c) {
        String site = c.getSiteInst() == null || c.getSiteInst().getSiteTypeEnum() == null ? null : c.getSiteInst().getSiteTypeEnum().name();
        String fam = belKeyFor(c), bare = c.getBELName();
        short v = -1;
        if (site != null) {
            v = tryIndex(dm, fam + "@" + site);
            if (v < 0 && !fam.equals(bare)) v = tryIndex(dm, bare + "@" + site);
        }
        if (v < 0) v = tryIndex(dm, fam);
        if (v < 0 && !fam.equals(bare)) v = tryIndex(dm, bare);
        if (v < 0 && site != null && bare.length() > 1 && bare.charAt(0) >= 'A' && bare.charAt(0) <= 'H' && "F56".indexOf(bare.charAt(1)) >= 0) {
            for (char l = 'A'; l <= 'H' && v < 0; l++) {
                if (l == bare.charAt(0)) continue;
                v = tryIndex(dm, l + fam.substring(1) + "@" + site);
            }
        }
        return v;
    }

    /**
     * Arcs of a cell that its configuration disables. The per-BEL tables hold the union of the arcs
     * Vivado lists over every configuration seen in training; a bypassed DSP58 register stage
     * (MREG=0 ...) has combinational arcs instead of clock-to-Q and checks, and a LOOKAHEAD8 output
     * takes either the lookahead inputs or the ripple carry of its own bit (LOOKx).
     */
    private static class ArcConfig {
        final Set<String> noLaunch = new HashSet<>();      // outputs whose clock-to-Q arcs do not exist (bypassed register)
        final Set<String> noCombInto = new HashSet<>();    // outputs whose combinational arcs do not exist (registered)
        final Set<String> noCheck = new HashSet<>();       // inputs whose timing checks do not exist (bypassed register)
        Map<String, Set<String>> allowedInto;              // output -> allowed input pins (LOOKAHEAD8); null = all
    }

    private static String busBase(String physPin) {
        int i = physPin.length();
        if (i > 2 && physPin.charAt(i - 1) == '_') {
            int j = i - 2;
            while (j > 0 && Character.isDigit(physPin.charAt(j))) j--;
            if (j < i - 2 && physPin.charAt(j) == '_') return physPin.substring(0, j);
        }
        return physPin;
    }

    private static final String[][] DSP_STAGES = {
        // BEL, property, threshold, outputs (bus base names), check inputs
        {"DSP_M_DATA", "MREG", "1", "U_DATA V_DATA", "U V CEM RSTM"},
        {"DSP_C_DATA", "CREG", "1", "C_DATA", "C CEC RSTC"},
        {"DSP_OUTPUT", "PREG", "1", "P P_FDBK P_FDBK_MSB PCOUT CARRYOUT CARRYCASCOUT CARRYCASCOUT_FB MULTSIGNOUT OVERFLOW UNDERFLOW PATTERNDETECT PATTERNBDETECT XOROUT", "ALU_OUT CEP RSTP"},
        {"DSP_A_B_DATA", "AREG", "1", "A2_DATA A_ALU", "A CEA2 RSTA"},
        {"DSP_A_B_DATA", "AREG", "2", "A1_DATA", "CEA1"},
        {"DSP_A_B_DATA", "ACASCREG", "1", "ACOUT", ""},
        {"DSP_A_B_DATA", "BREG", "1", "B2_DATA B_ALU", "B CEB2 RSTB"},
        {"DSP_A_B_DATA", "BREG", "2", "B1_DATA", "CEB1"},
        {"DSP_A_B_DATA", "BCASCREG", "1", "BCOUT", ""},
    };

    /**
     * Integer property of the outermost DSP-typed ancestor that carries it: the user's DSP58 primitive.
     * The leaf cells RapidWright expands the macro into carry library defaults (MREG=1 ...) that do
     * not reflect the instance.
     */
    private static Integer intProperty(EDIFHierCellInst h, String name) {
        Integer found = null;
        for (EDIFHierCellInst x = h; x != null; x = x.getParent()) {
            if (x.getInst() == null || x.getCellType() == null) break;
            EDIFPropertyValue pv = x.getInst().getProperty(name);
            if (pv != null) {
                try { found = Integer.parseInt(pv.getValue().trim()); } catch (NumberFormatException e) { /* keep the previous */ }
            }
            if (!x.getCellType().getName().startsWith("DSP")) break;
        }
        return found;
    }

    private ArcConfig arcConfig(Cell c, List<String> ins, List<String> outs) {
        String bel = c.getBELName(), type = c.getType();
        if (bel == null || type == null) return null;
        if (type.startsWith("DSP_")) {
            EDIFHierCellInst h = c.getEDIFHierCellInst();
            if (h == null) return null;
            ArcConfig cfg = null;
            for (String[] st : DSP_STAGES) {
                if (!bel.equals(st[0])) continue;
                Integer v = intProperty(h, st[1]);
                if (v == null) continue;
                boolean registered = v >= Integer.parseInt(st[2]);
                if (cfg == null) cfg = new ArcConfig();
                Set<String> outNames = new HashSet<>(Arrays.asList(st[3].split(" ")));
                Set<String> inNames = st[4].isEmpty() ? Collections.emptySet() : new HashSet<>(Arrays.asList(st[4].split(" ")));
                for (String o : outs) if (outNames.contains(busBase(o))) (registered ? cfg.noCombInto : cfg.noLaunch).add(o);
                if (!registered) for (String i : ins) if (inNames.contains(busBase(i))) cfg.noCheck.add(i);
            }
            return cfg;
        }
        if (type.equals("LOOKAHEAD8") && c.getEDIFCellInst() != null) {
            // COUTx: lookahead (LOOKx=TRUE) from the carry entering its segment over the GE/PROP of the bits
            // since the last ripple boundary, or ripple (FALSE) from its own bit's CY and GE; COUTH is always
            // the full lookahead from CIN (observed in Vivado's per-cell arcs)
            ArcConfig cfg = new ArcConfig();
            cfg.allowedInto = new HashMap<>();
            String bits = "ABCDEFGH";
            Set<String> carry = new HashSet<>(Collections.singletonList("CIN"));   // inputs that determine the carry into the current segment
            int from = 0;
            for (char x : new char[] {'B', 'D', 'F'}) {
                int xi = bits.indexOf(x);
                EDIFPropertyValue pv = c.getEDIFCellInst().getProperty("LOOK" + x);
                boolean look = pv == null || !pv.getValue().trim().equalsIgnoreCase("FALSE");
                Set<String> allowed = new HashSet<>();
                if (look) {
                    allowed.addAll(carry);
                    for (int b = from; b <= xi; b++) { allowed.add("GE" + bits.charAt(b)); allowed.add("PROP" + bits.charAt(b)); }
                } else {
                    allowed.add("CY" + x);
                    allowed.add("GE" + x);
                }
                cfg.allowedInto.put("COUT" + x, allowed);
                carry = allowed;
                from = xi + 1;
            }
            Set<String> all = new HashSet<>(Collections.singletonList("CIN"));
            for (int b = 0; b < 8; b++) { all.add("GE" + bits.charAt(b)); all.add("PROP" + bits.charAt(b)); }
            cfg.allowedInto.put("COUTH", all);
            return cfg;
        }
        return null;
    }

    /** What one cell adds to the graph, resolved off the graph (parallel) and applied in cell order. */
    private static final class CellPlan {
        final Cell cell;
        String unknownBel;          // type@BEL when the delay model has no section for it
        int pruned;
        /** in order: {in, out, delays or null} for a clock-to-output launch (clock input) ... */
        final List<Object[]> launches = new ArrayList<>();
        /** ... {in, out, delays} for a combinational arc ... */
        final List<Object[]> arcs = new ArrayList<>();
        /** ... and {in, check delays or null, clock pin} for an endpoint; launches and arcs interleave in input order, so they carry a sequence number */
        final List<Object[]> checks = new ArrayList<>();
        /** ordered stream of the launch and arc entries: true = launch, false = arc */
        final List<Boolean> order = new ArrayList<>();
        CellPlan(Cell cell) { this.cell = cell; }
    }

    private void buildLogicArcs() {
        List<Cell> cells = new ArrayList<>();
        for (Cell c : design.getCells()) {
            if (c.getBEL() == null || c.isRoutethru()) continue;
            cells.add(c);
        }
        // phase 1 (parallel): per cell, BEL indices, arc configuration, delay lookups, LUT pruning
        CellPlan[] plans = new CellPlan[cells.size()];
        java.util.stream.IntStream range = java.util.stream.IntStream.range(0, cells.size());
        (DEBUG_CELL == null && com.xilinx.rapidwright.util.ParallelismTools.getParallel() ? range.parallel() : range)
                .forEach(i -> plans[i] = planCell(cells.get(i)));
        // phase 2 (sequential, cell order): the same vertices, launches, edges and endpoints in the same order
        for (CellPlan p : plans) {
            Cell c = p.cell;
            if (p.unknownBel != null) { unknownBels++; unknownBelNames.add(p.unknownBel); continue; }
            prunedLutArcs += p.pruned;
            int li = 0, ai = 0;
            for (boolean isLaunch : p.order) {
                if (isLaunch) {
                    Object[] l = p.launches.get(li++);
                    String in = (String) l[0], out = (String) l[1];
                    float[] d = (float[]) l[2];
                    Vertex q = vertex(c, out);
                    for (int i = 0; i < nc; i++) {
                        float clkq = d == null ? 0 : Math.max(0, d[i]);
                        q.arrival[i] = q.launch ? (model.isMax(i) ? Math.max(q.arrival[i], clkq) : Math.min(q.arrival[i], clkq)) : clkq;
                    }
                    q.launch = true;
                    clkToQ.put(q, q.arrival.clone());
                    clockPin.put(q, in);
                } else {
                    Object[] a = p.arcs.get(ai++);
                    addEdge(vertex(c, (String) a[0]), vertex(c, (String) a[1]), (float[]) a[2], null, "logic");
                }
            }
            for (Object[] k : p.checks) {
                Vertex v = vertex(c, (String) k[0]);
                v.endpoint = true;
                float[] chk = (float[]) k[1];
                if (chk != null) System.arraycopy(chk, 0, v.check, 0, nc);
                endpoints.add(v);
                clockPin.put(v, (String) k[2]);
            }
        }
    }

    private CellPlan planCell(Cell c) {
        CellPlan plan = new CellPlan(c);
        short[] belIdx = belIndices(c);
        if (belIdx == null) { plan.unknownBel = c.getType() + "@" + c.getBELName(); return plan; }
        Map<String, String> p2l = c.getPinMappingsP2L();
        List<String> ins = new ArrayList<>(), outs = new ArrayList<>();
        for (String phys : p2l.keySet()) {
            BELPin bp = c.getBEL().getPin(phys);
            if (bp == null) continue;
            (bp.isInput() ? ins : outs).add(phys);
        }
        boolean ff = isFlipFlop(c);
        List<String> clocks = new ArrayList<>(1);
        for (String in : ins) if (isClockPin(in)) clocks.add(in);
        boolean sequential = ff || !clocks.isEmpty();
        long lutInit = -1; int lutSize = 0;
        if (c.getType() != null && c.getType().startsWith("LUT")) {
            lutSize = LUTTools.getLUTSize(c);
            lutInit = lutInitValue(c);
        }
        ArcConfig cfg = arcConfig(c, ins, outs);
        boolean dbg = DEBUG_CELL != null && c.getName().contains(DEBUG_CELL);
        if (dbg && cfg != null) System.out.println("[debug cell] arc config: noLaunch " + cfg.noLaunch + " noCombInto " + cfg.noCombInto + " noCheck " + cfg.noCheck + " allowedInto " + cfg.allowedInto);
        if (dbg) System.out.println("[debug cell] " + c.getName() + " type " + c.getType() + " bel " + c.getBELName() + " ins " + ins + " outs " + outs + " lutSize " + lutSize + " init " + Long.toHexString(lutInit) + " belIdx " + belIdx[0] + " p2l " + p2l);
        for (String in : ins) {
            // Vivado has no timing arc from a constant pin, nor from a LUT input the INIT function
            // does not depend on (given the other inputs that are constant)
            if (constantPins.containsKey(c.getName() + "/" + in)) { plan.pruned++; if (dbg) System.out.println("[debug cell]   " + in + " constant"); continue; }
            if (lutInit >= 0 && !lutDependsOnGivenConstants(c, in, lutInit, lutSize)) { plan.pruned++; if (dbg) System.out.println("[debug cell]   " + in + " pruned (INIT-independent)"); continue; }
            if (dbg) for (String out : outs) System.out.println("[debug cell]   " + in + " -> " + out + " = " + java.util.Arrays.toString(logicDelays(belIdx, in, out)));
            boolean clockIn = sequential && (isClockPin(in) || (ff && in.equals("CLK")));
            for (String out : outs) {
                float[] d = logicDelays(belIdx, in, out);
                if (cfg != null && !clockIn) {
                    if (cfg.noCombInto.contains(out)) continue;
                    Set<String> allowed = cfg.allowedInto == null ? null : cfg.allowedInto.get(out);
                    if (allowed != null && !allowed.contains(in)) continue;
                }
                if (clockIn) {
                    // clock-to-output launches the data path (a flop still launches at 0 without an arc)
                    if (d == null && !ff) continue;
                    if (cfg != null && cfg.noLaunch.contains(out)) continue;
                    plan.launches.add(new Object[] {in, out, d});
                    plan.order.add(Boolean.TRUE);
                    continue;
                }
                if (d == null) continue;
                plan.arcs.add(new Object[] {in, out, d});
                plan.order.add(Boolean.FALSE);
            }
        }
        if (sequential) {
            for (String in : ins) {
                if (isClockPin(in) || (ff && in.equals("CLK"))) continue;
                if (cfg != null && cfg.noCheck.contains(in)) continue;
                // an input is an endpoint if it has a timing check from a clock pin (flop data/control
                // pins always are)
                float[] chk = null;
                for (String clk : (ff && clocks.isEmpty() ? Collections.singletonList("CLK") : clocks)) {
                    chk = logicDelays(belIdx, clk, in);
                    if (chk != null) break;
                }
                if (chk == null && !ff) continue;
                plan.checks.add(new Object[] {in, chk, clocks.isEmpty() ? "CLK" : clocks.get(0)});
            }
        }
        return plan;
    }

    /** What one net adds to the graph, resolved off the graph (parallel) and applied in net order. */
    private static final class NetPlan {
        final Net net;
        Cell srcCell; String srcPhys;
        /** {sink cell, physical pin, delays or null when unrouted, kind}: in sink port order, unrouted sinks included (their vertex is still created) */
        final List<Object[]> sinks = new ArrayList<>();
        int unrouted, skipped;
        NetPlan(Net net) { this.net = net; }
    }

    /** Whether the per-net resolution runs across threads (RW_PARALLEL honoured; serial under DEBUG_SINK to keep its output in order). */
    static boolean parallelNets() {
        return DEBUG_SINK == null && com.xilinx.rapidwright.util.ParallelismTools.getParallel();
    }

    private void buildNetEdges() {
        List<Net> nets = new ArrayList<>();
        for (Net net : design.getNets()) {
            if (net.getType() != NetType.WIRE || net.isStaticNet() || net.isClockNet()) continue;
            nets.add(net);
        }
        // phase 1 (parallel): pure lookups against the design, the device and the model's tables
        NetPlan[] plans = new NetPlan[nets.size()];
        java.util.stream.IntStream range = java.util.stream.IntStream.range(0, nets.size());
        (parallelNets() ? range.parallel() : range).forEach(i -> plans[i] = planNet(nets.get(i)));
        // phase 2 (sequential, net order): vertices and edges in the same order as before, so the
        // topological queue and every tie-break are unchanged
        for (NetPlan p : plans) {
            netsSkipped += p.skipped;
            unroutedSinks += p.unrouted;
            if (p.srcCell == null) continue;
            Vertex vs = vertex(p.srcCell, p.srcPhys);
            for (Object[] sk : p.sinks) {
                Vertex vd = vertex((Cell) sk[0], (String) sk[1]);
                if (sk[2] != null) addEdge(vs, vd, (float[]) sk[2], p.net, (String) sk[3], (SitePinInst) sk[4], (float[]) sk[5]);
            }
        }
    }

    private NetPlan planNet(Net net) {
        NetPlan plan = new NetPlan(net);
        EDIFHierNet hnet = design.getNetlist().getHierNetFromName(net.getName());
        if (hnet == null) { plan.skipped++; return plan; }
        EDIFHierPortInst srcPort = null;
        List<EDIFHierPortInst> sinkPorts = new ArrayList<>();
        for (EDIFHierPortInst p : hnet.getLeafHierPortInsts(true, true)) {
            if (p.isOutput()) { if (srcPort == null) srcPort = p; } else sinkPorts.add(p);
        }
        if (srcPort == null) { plan.skipped++; return plan; }
        Cell srcCell = srcPort.getPhysicalCell(design);
        if (srcCell == null || srcCell.getBEL() == null) { plan.skipped++; return plan; }
        String srcPhys = srcCell.getPhysicalPinMapping(srcPort.getPortInst().getName());
        if (srcPhys == null) { plan.skipped++; return plan; }
        if (srcCell.getType().startsWith("BUFG") || srcCell.getType().startsWith("MBUFG")) return plan;
        plan.srcCell = srcCell;
        plan.srcPhys = srcPhys;

        // sinks reached through site pins
        Map<SitePinInst, VersalTimingModel.SinkDelay> sinkDelays =
                net.getSource() != null ? model.calcNetDelays(net) : Collections.emptyMap();
        if (sinkDelays.isEmpty() && edgesForUnroutedSinks && !net.getSinkPins().isEmpty()) {
            // no source pin yet (a router will create it): every sink is unrouted, intra-site terms only
            sinkDelays = new LinkedHashMap<>();
            for (SitePinInst sink : net.getSinkPins()) {
                VersalTimingModel.SinkDelay sd = new VersalTimingModel.SinkDelay(nc);
                sd.routed = false;
                sd.sinkIntraSite = model.sinkIntraSiteDelays(sink);
                sinkDelays.put(sink, sd);
            }
        }
        // the driver-side intra-site term of an unrouted sink (calcNetDelays leaves it 0 without a route)
        float[] unroutedDriver = null;
        Map<Cell, Map<String, Object[]>> viaSitePin = new HashMap<>();   // {totals, intra-site, site pin}
        // hard-block sites (DSP58): the site pin's connected BEL pins are not the placed cell's; match
        // the sink site pin to the cell's BEL pin by name (C44 <-> C_44_)
        Map<SiteInst, Map<String, Object[]>> byName = new HashMap<>();
        for (Map.Entry<SitePinInst, VersalTimingModel.SinkDelay> e : sinkDelays.entrySet()) {
            SitePinInst spi = e.getKey();
            SiteInst si = spi.getSiteInst();
            if (si == null) continue;
            VersalTimingModel.SinkDelay sd = e.getValue();
            if (!sd.routed) {
                plan.unrouted++;
                if (!edgesForUnroutedSinks) continue;
                if (unroutedDriver == null) {
                    unroutedDriver = net.getSource() != null ? model.driverIntraSiteDelays(net.getSource(), net) : new float[nc];
                }
                sd.driverIntraSite = unroutedDriver;
            }
            float[] intra = new float[nc];
            for (int i = 0; i < nc; i++) intra[i] = sd.driverIntraSite[i] + sd.sinkIntraSite[i];
            Object[] info = new Object[] {sd.getTotals(), intra, spi};
            byName.computeIfAbsent(si, k -> new HashMap<>()).put(spi.getName().replace("_", ""), info);
            for (BELPin bp : DesignTools.getConnectedBELPins(spi)) {
                if (!bp.isInput()) continue;
                Cell c = si.getCell(bp.getBEL());
                if (c == null) continue;
                viaSitePin.computeIfAbsent(c, k -> new HashMap<>()).put(bp.getName(), info);
            }
        }
        for (EDIFHierPortInst sp : sinkPorts) {
            Cell dc = sp.getPhysicalCell(design);
            boolean dbgSink = DEBUG_SINK != null && sp.toString().contains(DEBUG_SINK);
            if (dbgSink) System.out.println("[debug sink] " + sp.toString() + " net " + net.getName() + " cell " + dc + " bel " + (dc == null ? null : dc.getBELName())
                    + " phys " + (dc == null ? null : dc.getPhysicalPinMapping(sp.getPortInst().getName())) + " viaSitePin " + (dc == null ? null : viaSitePin.get(dc))
                    + " sinkDelays " + sinkDelays.size() + " netSinkPins " + net.getSinkPins().size() + " src " + srcCell + "/" + srcPhys + " pips " + net.getPIPs().size());
            if (dc == null || dc.getBEL() == null) continue;
            String dphys = dc.getPhysicalPinMapping(sp.getPortInst().getName());
            if (dphys == null) continue;
            Object[] viaPin = viaSitePin.getOrDefault(dc, Collections.emptyMap()).get(dphys);
            if (viaPin == null && dc.getSiteInst() != null && dc.getSiteInst() != srcCell.getSiteInst()) {
                viaPin = byName.getOrDefault(dc.getSiteInst(), Collections.emptyMap()).get(dphys.replace("_", ""));
                if (dbgSink) System.out.println("[debug sink]   by-name site pin " + dphys.replace("_", "") + " -> " + (viaPin == null ? "none" : viaPin[2]));
            }
            if (viaPin != null) {
                plan.sinks.add(new Object[] {dc, dphys, viaPin[0], "net", viaPin[2], viaPin[1]});
            } else if (dc.getSiteInst() == srcCell.getSiteInst()) {
                BELPin from = srcCell.getBEL().getPin(srcPhys), to = dc.getBEL().getPin(dphys);
                plan.sinks.add(new Object[] {dc, dphys, model.intraSiteNetDelays(dc.getSiteInst(), from, to), "intrasite", null, null});
            } else {
                plan.sinks.add(new Object[] {dc, dphys, null, null, null, null});
                plan.unrouted++;
            }
        }
        return plan;
    }

    /**
     * Resets every non-launch arrival so that {@link #computeArrivals()} can be run again (e.g. after
     * seeding the launches with clock arrivals).
     */
    public void resetArrivals() {
        for (Vertex v : vertices.values()) {
            if (v.launch) continue;
            for (int i = 0; i < nc; i++) { v.arrival[i] = unset(i); v.pred[i] = null; }
            v.tags = null;
        }
    }

    /** Physical clock pin of a launch or endpoint vertex's cell, or null. */
    public String getClockPin(Vertex v) {
        return clockPin.get(v);
    }

    /** Launch vertices (clock-to-output pins of sequential cells). */
    public List<Vertex> getLaunches() {
        return new ArrayList<>(clkToQ.keySet());
    }

    /** Clock-to-output delay (per corner) of a launch vertex, before any clock arrival; null for other vertices. */
    public float[] getClkToQ(Vertex v) {
        return clkToQ.get(v);
    }

    /**
     * Sets a launch vertex's arrival to its clock arrival plus clock-to-output delay, per corner.
     * Call after {@link #build()} and before {@link #computeArrivals()}.
     */
    /** Removes a launch from the analysis (no clock arrival known): its arrival becomes unset. */
    public void unseedLaunch(Vertex v) {
        for (int i = 0; i < nc; i++) v.arrival[i] = unset(i);
        v.tags = null;
    }

    public void seedLaunch(Vertex v, float[] clockArrival) {
        seedLaunch(v, clockArrival, null);
    }

    /** Seeds a launch and puts it in the clock group {@code tag} (see {@link Tagged}). */
    public void seedLaunch(Vertex v, float[] clockArrival, Object tag) {
        float[] q = clkToQ.get(v);
        if (q == null) return;
        for (int i = 0; i < nc; i++) v.arrival[i] = clockArrival[i] + q[i];
        v.tags = new ArrayList<>(1);
        v.tags.add(launchTag(v, tag));
    }

    private Tagged launchTag(Vertex v, Object tag) {
        Tagged t = new Tagged(tag, nc);
        System.arraycopy(v.arrival, 0, t.arrival, 0, nc);
        return t;
    }

    /** The arrival group of a vertex with the given tag, or null. */
    public static Tagged getTag(Vertex v, Object tag) {
        if (v.tags == null) return null;
        for (Tagged t : v.tags) if (t.tag == tag || (tag != null && tag.equals(t.tag))) return t;
        return null;
    }

    /** All arrival groups of a vertex (empty when unreached). */
    public static List<Tagged> getTags(Vertex v) {
        return v.tags == null ? Collections.emptyList() : v.tags;
    }

    /**
     * Propagates arrival times from the launch vertices for every corner (longest path at max
     * corners, shortest at min corners); returns the endpoints reached at the primary corner,
     * sorted worst first. A launch vertex with incoming combinational edges (SRL / LUT-RAM output
     * from the address pins) ends up with the extreme of its clock-to-Q and the paths into it.
     */
    public List<Vertex> computeArrivals() {
        // Kahn topological order restricted to the reachable graph
        Map<Vertex, Integer> indeg = new HashMap<>();
        for (Edge e : edges) indeg.merge(e.dst, 1, Integer::sum);
        Deque<Vertex> queue = new ArrayDeque<>();
        for (Vertex v : vertices.values()) if (!indeg.containsKey(v)) queue.add(v);
        // launches never seeded through seedLaunch (clock-to-Q only) form the default group
        for (Vertex v : clkToQ.keySet()) if (v.tags == null && isSet(v.arrival[0])) { v.tags = new ArrayList<>(1); v.tags.add(launchTag(v, null)); }
        int visited = 0;
        while (!queue.isEmpty()) {
            Vertex v = queue.poll();
            visited++;
            for (Edge e : v.outs) {
                // a launch vertex can also be the output of a combinational arc (an SRL or LUT-RAM output
                // reached from its address pins, a bypassed DSP register): its arrival is the extreme of its
                // own clock-to-Q and the paths into it, so edges into launches are relaxed like any other
                {
                    for (int i = 0; i < nc; i++) {
                        if (!isSet(v.arrival[i])) continue;
                        float a = v.arrival[i] + e.delay[i];
                        if (better(i, a, e.dst.arrival[i])) { e.dst.arrival[i] = a; e.dst.pred[i] = e; }
                    }
                    if (v.tags != null) {
                        for (Tagged s : v.tags) {
                            Tagged d = getTag(e.dst, s.tag);
                            if (d == null) {
                                d = new Tagged(s.tag, nc);
                                for (int i = 0; i < nc; i++) d.arrival[i] = unset(i);
                                if (e.dst.tags == null) e.dst.tags = new ArrayList<>(1);
                                e.dst.tags.add(d);
                            }
                            for (int i = 0; i < nc; i++) {
                                if (!isSet(s.arrival[i])) continue;
                                float a = s.arrival[i] + e.delay[i];
                                if (better(i, a, d.arrival[i])) { d.arrival[i] = a; d.pred[i] = e; }
                            }
                        }
                    }
                }
                int d = indeg.merge(e.dst, -1, Integer::sum);
                if (d == 0) queue.add(e.dst);
            }
        }
        if (visited != vertices.size()) {
            System.err.println("WARNING: timing graph has a combinational loop; " + (vertices.size() - visited) + " vertices not visited");
        }
        return getEndpointsSorted(0);
    }

    /** Endpoints reached at a corner, worst first (longest arrival at max corners, shortest at min corners). */
    public List<Vertex> getEndpointsSorted(int corner) {
        List<Vertex> sorted = new ArrayList<>();
        for (Vertex v : endpoints) if (isSet(v.arrival[corner])) sorted.add(v);
        boolean max = model.isMax(corner);
        sorted.sort((a, b) -> max ? Float.compare(b.arrival[corner], a.arrival[corner]) : Float.compare(a.arrival[corner], b.arrival[corner]));
        return sorted;
    }

    /**
     * Slack of an endpoint at a corner: setup slack {@code period - arrival - setup} at max corners,
     * hold slack {@code arrival - hold} at min corners (no clock skew or uncertainty).
     */
    public float getSlack(Vertex v, int corner, float periodPs) {
        return model.isMax(corner) ? periodPs - v.arrival[corner] - v.check[corner] : v.arrival[corner] - v.check[corner];
    }

    /** Path from the launching vertex to the given endpoint at the primary corner. */
    public List<Edge> getPath(Vertex end) {
        return getPath(end, 0);
    }

    /** Path from the launching vertex to the given endpoint at a corner. */
    public List<Edge> getPath(Vertex end, int corner) {
        List<Edge> path = new ArrayList<>();
        for (Edge e = end.pred[corner]; e != null; e = e.src.pred[corner]) path.add(e);
        Collections.reverse(path);
        return path;
    }

    /** Path of one arrival group (see {@link Tagged}) into the endpoint at a corner; empty if the group does not reach it. */
    public List<Edge> getPath(Vertex end, int corner, Object tag) {
        List<Edge> path = new ArrayList<>();
        Tagged t = getTag(end, tag);
        while (t != null && t.pred[corner] != null) {
            Edge e = t.pred[corner];
            path.add(e);
            t = getTag(e.src, tag);
        }
        Collections.reverse(path);
        return path;
    }

    public String formatPath(Vertex end) {
        return formatPath(end, 0);
    }

    /**
     * The worst (max corners: longest; min corners: shortest) path from one launch to an endpoint through
     * the built graph, or null if the endpoint is not reachable from that launch. Unlike {@link #getPath},
     * which follows the globally worst predecessor into the endpoint, this restricts the search to the
     * given start, for comparing a specific Vivado path.
     */
    public List<Edge> getPathFrom(Vertex launch, Vertex end, int corner) {
        boolean max = model.isMax(corner);
        // reachable subgraph from the launch (endpoints are not traversed through)
        Set<Vertex> reach = new HashSet<>();
        Deque<Vertex> stack = new ArrayDeque<>();
        reach.add(launch); stack.push(launch);
        while (!stack.isEmpty()) {
            Vertex v = stack.pop();
            if (v != launch && v.endpoint) continue;
            for (Edge e : v.outs) if (reach.add(e.dst)) stack.push(e.dst);
        }
        if (!reach.contains(end)) return null;
        // topological order of the reachable subgraph (Kahn), then relax
        Map<Vertex, Integer> indeg = new HashMap<>();
        for (Vertex v : reach) if (v == launch || !v.endpoint) for (Edge e : v.outs) if (reach.contains(e.dst)) indeg.merge(e.dst, 1, Integer::sum);
        Deque<Vertex> q = new ArrayDeque<>();
        for (Vertex v : reach) if (indeg.getOrDefault(v, 0) == 0) q.add(v);
        Map<Vertex, Float> dist = new HashMap<>();
        Map<Vertex, Edge> back = new HashMap<>();
        dist.put(launch, launch.arrival[corner]);
        while (!q.isEmpty()) {
            Vertex v = q.poll();
            Float dv = dist.get(v);
            if (v == launch || !v.endpoint) for (Edge e : v.outs) {
                if (!reach.contains(e.dst)) continue;
                if (dv != null) {
                    float cand = dv + e.delay[corner];
                    Float cur = dist.get(e.dst);
                    if (cur == null || (max ? cand > cur : cand < cur)) { dist.put(e.dst, cand); back.put(e.dst, e); }
                }
                if (indeg.merge(e.dst, -1, Integer::sum) == 0) q.add(e.dst);
            }
        }
        if (!dist.containsKey(end)) return null;
        List<Edge> path = new ArrayList<>();
        for (Edge e = back.get(end); e != null; e = back.get(e.src)) path.add(e);
        Collections.reverse(path);
        return path;
    }

    /** Arrival at the end of a path (launch clock-to-Q plus the edges), at a corner. */
    public float pathArrival(List<Edge> path, Vertex end, int corner) {
        Vertex start = path.isEmpty() ? end : path.get(0).src;
        float acc = start.arrival[corner];
        for (Edge e : path) acc += e.delay[corner];
        return acc;
    }

    public String formatPath(Vertex end, int corner) {
        return formatPath(getPath(end, corner), end, corner);
    }

    /** Formats an explicit path (see {@link #getPathFrom}) the way {@link #formatPath(Vertex, int)} does. */
    public String formatPath(List<Edge> path, Vertex end, int corner) {
        StringBuilder sb = new StringBuilder();
        Vertex start = path.isEmpty() ? end : path.get(0).src;
        float logic = start.launch ? start.arrival[corner] : 0, netd = 0;
        sb.append(String.format("  [%s] %-9s %8.0f  %s (%s clk-to-Q)%n", model.getCorner(corner).getSuffix(), "launch", start.arrival[corner], start, start.cell.getType()));
        float acc = start.arrival[corner];
        for (Edge e : path) {
            acc += e.delay[corner];
            if (e.kind.equals("logic")) logic += e.delay[corner]; else netd += e.delay[corner];
            sb.append(String.format("  %-9s %8.0f  %8.0f  %s%s%n", e.kind, e.delay[corner], acc, e.dst,
                    e.net != null ? "  [" + e.net.getName() + "]" : "  (" + e.dst.cell.getType() + ")"));
        }
        sb.append(String.format("  data path %.0f ps (logic %.0f, net %.0f), %s %.0f ps%n", acc, logic, netd,
                model.isMax(corner) ? "setup" : "hold", end.check[corner]));
        return sb.toString();
    }

    /** {logic, net} delay sums along the critical path into a vertex at the primary corner (logic includes clk-to-Q). */
    public float[] pathParts(Vertex end) {
        return pathParts(end, 0);
    }

    public float[] pathParts(Vertex end, int corner) {
        float logic = 0, net = 0;
        List<Edge> path = getPath(end, corner);
        Vertex start = path.isEmpty() ? end : path.get(0).src;
        if (start.launch) logic += start.arrival[corner];
        for (Edge e : path) { if (e.kind.equals("logic")) logic += e.delay[corner]; else net += e.delay[corner]; }
        return new float[] {logic, net};
    }

    /**
     * A short multi-corner report: for every corner of the model the worst endpoint and its path,
     * with slacks against the given clock period (0 to omit).
     */
    public String report(float periodPs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nc; i++) {
            List<Vertex> ends = getEndpointsSorted(i);
            if (ends.isEmpty()) continue;
            Vertex worst = ends.get(0);
            sb.append(String.format("corner %s (%s): %d endpoints, worst %s at %.0f ps", model.getCorner(i).getSuffix(),
                    model.isMax(i) ? "setup" : "hold", ends.size(), worst, worst.arrival[i]));
            if (periodPs > 0 && model.isMax(i)) sb.append(String.format(", slack %.0f ps", getSlack(worst, i, periodPs)));
            if (!model.isMax(i)) sb.append(String.format(", hold slack %.0f ps", getSlack(worst, i, periodPs)));
            sb.append('\n').append(formatPath(worst, i));
        }
        return sb.toString();
    }

    /** Debug helper: the edges feeding a vertex and their sources' arrivals (primary corner). */
    public String describeFanin(Vertex v) {
        StringBuilder sb = new StringBuilder();
        for (Edge e : edges) {
            if (e.dst == v) sb.append(String.format("[%s %s arr=%.0f d=%.0f] ", e.kind, e.src, e.src.arrival[0], e.delay[0]));
        }
        if (sb.length() == 0) return "(no fanin edges)";
        // follow the first unreached predecessor back to where the chain breaks
        Vertex cur = v;
        for (int depth = 0; depth < 12; depth++) {
            Vertex next = null;
            for (Edge e : edges) if (e.dst == cur && Float.isInfinite(e.src.arrival[0])) { next = e.src; break; }
            if (next == null) break;
            boolean any = false;
            for (Edge e : edges) if (e.dst == next) any = true;
            sb.append(String.format(" <- %s (%s@%s)%s", next, next.cell.getType(), next.cell.getBELName(), any ? "" : " NO FANIN"));
            cur = next;
        }
        return sb.toString();
    }

    public int getPrunedLutArcCount() { return prunedLutArcs; }
    public int getConstantLutCount() { return constantLuts; }
    public int getVertexCount() { return vertices.size(); }
    public int getEdgeCount() { return edges.size(); }
    public int getUnknownBelCount() { return unknownBels; }
    public Set<String> getUnknownBelNames() { return unknownBelNames; }
    public int getUnroutedSinkCount() { return unroutedSinks; }
    public int getSkippedNetCount() { return netsSkipped; }
    public List<Vertex> getEndpoints() { return endpoints; }

    /** A cell's logic arc from one BEL pin to another (per corner, model order), or null if its BEL has no such arc. */
    public float[] cellArc(Cell c, String in, String out) {
        short[] idx = belIndices(c);
        return idx == null ? null : logicDelays(idx, in, out);
    }
    public List<Edge> getEdges() { return edges; }
    public java.util.Collection<Vertex> getVertices() { return vertices.values(); }
}
