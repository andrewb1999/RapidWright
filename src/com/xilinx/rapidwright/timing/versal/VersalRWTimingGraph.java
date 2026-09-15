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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.timing.DelayModelBuilder;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.util.RuntimeTrackerTree;

/**
 * The {@link TimingGraph} RWRoute uses on Versal: the data-path graph of {@link VersalTimingGraph}
 * (logic arcs, intra-site and net edges of the Versal model at the slow-max corner) copied into the
 * {@link TimingVertex} / {@link TimingEdge} form the router's static timing analysis and criticality
 * computation work on, so that everything from {@code TimingManager} down is shared with UltraScale+.
 *
 * <p>Differences from the UltraScale+ build: launches are the clock-to-output pins of sequential
 * cells and get their clock-to-Q on the edge from the super source; endpoints are the data/control
 * inputs of sequential cells (flip-flops, DSP58, BRAM, SRL) and get their setup check on the edge to
 * the super sink; a sink whose site pin is not routed yet has a net edge carrying only the intra-site
 * terms, which {@link Connection#updateRouteDelay()} completes once the connection is routed; the
 * connection-to-edges mapping is keyed by the sink site pin directly.
 *
 * <p>With clock skew enabled ({@code --versalClockSkew}), {@link #applyClockArrivals} folds the
 * clock model's arrivals into the same two super edges once the clock nets are routed, so the
 * router's slacks include skew and pessimism removal at no cost in the routing iterations; see
 * that method for the exact terms.
 */
public class VersalRWTimingGraph extends TimingGraph {

    private VersalTimingModel model;
    private final boolean clockSkew;
    /** launch vertex -> clock-to-output delay (ps) */
    private final Map<TimingVertex, Float> launchClkToQ = new LinkedHashMap<>();
    /** endpoint vertex -> setup requirement (ps) */
    private final Map<TimingVertex, Float> endpointCheck = new LinkedHashMap<>();
    private int connectionsWithoutEdges = 0;

    /** the Versal graph and its launch/endpoint vertices, kept from the build until {@link #applyClockArrivals} (clock skew only) */
    private VersalTimingGraph versalGraph;
    private Map<TimingVertex, VersalTimingGraph.Vertex> clockedVertex;
    /** clock terms folded into the super edges by {@link #applyClockArrivals}: see there */
    private float clockOffset = 0f;
    private Map<TimingVertex, Float> launchClockTerm, sinkClockTerm;
    private boolean clockArrivalsApplied = false;

    public VersalRWTimingGraph(Design design, RuntimeTrackerTree timer) {
        this(design, timer, false);
    }

    /**
     * @param clockSkew keep what {@link #applyClockArrivals} needs (the Versal graph until the clocks
     *        are routed) and build the model with the slow-min corner as well
     */
    public VersalRWTimingGraph(Design design, RuntimeTrackerTree timer, boolean clockSkew) {
        super(design, timer, null, null);
        this.clockSkew = clockSkew;
    }

    public VersalTimingModel getModel() {
        return model;
    }

    /** A slow-max-only Versal timing model (the corner RWRoute's setup-driven routing works at). */
    public static VersalTimingModel createRouterModel(Design design) {
        return createRouterModel(design, false);
    }

    /**
     * The router's Versal timing model: slow max only, or slow max and slow min when the clock
     * arrivals are wanted (the capture clock is timed at the min corner, and the clock tree analysis
     * reads both corners of the data model for the buffer's own arcs).
     */
    public static VersalTimingModel createRouterModel(Design design, boolean withMinCorner) {
        if (withMinCorner) {
            return new VersalTimingModel(design.getDevice(), VersalCorner.SLOW_MAX, VersalCorner.SLOW_MIN);
        }
        VersalCorner corner = VersalCorner.SLOW_MAX;
        return new VersalTimingModel(design.getDevice(), corner, new VersalDelayTerms(corner),
                DelayModelBuilder.getDelayModel("versal", corner.getSuffix()));
    }

    @Override
    public void build(boolean isPartialRouting, Collection<Net> targetNets) {
        Design design = getDesign();
        if (model == null) {
            model = createRouterModel(design, clockSkew);
        }
        VersalTimingGraph vg = new VersalTimingGraph(design, model, true);
        vg.build();

        Map<VersalTimingGraph.Vertex, TimingVertex> map = new HashMap<>();
        if (clockSkew) clockedVertex = new LinkedHashMap<>();
        for (VersalTimingGraph.Vertex v : vg.getVertices()) {
            TimingVertex tv = new TimingVertex(v.getName());
            if (v.endpoint) tv.setFlopInput();
            if (v.launch) tv.setFlopOutput();
            tv = safeAddVertex(tv);
            map.put(v, tv);
            if (v.launch) {
                float[] q = vg.getClkToQ(v);
                launchClkToQ.put(tv, q == null ? 0f : Math.max(0f, q[0]));
            }
            if (v.endpoint) endpointCheck.put(tv, v.check[0]);
            if (clockSkew && (v.launch || v.endpoint)) clockedVertex.put(tv, v);
        }
        for (VersalTimingGraph.Edge e : vg.getEdges()) {
            TimingVertex s = map.get(e.src), d = map.get(e.dst);
            TimingEdge te = new TimingEdge(this, s, d, null, e.net);
            switch (e.kind) {
                case "logic":
                    te.setLogicDelay(e.delay[0]);
                    break;
                case "intrasite":
                    te.setIntraSiteDelay(e.delay[0]);
                    te.setNetDelay(e.delay[0]);
                    break;
                default: { // "net"
                    float intra = e.intraSite == null ? 0f : e.intraSite[0];
                    te.setIntraSiteDelay(intra);
                    te.setNetDelay(Math.max(intra, e.delay[0]));
                    te.setSecondSitePinInst(e.sinkPin);
                    if (e.net != null) te.setFirstSitePinInst(e.net.getSource());
                    break;
                }
            }
            if (!safeAddEdge(s, d, te)) continue;
            setEdgeWeight(te, te.getDelay());
            if (e.sinkPin != null) {
                sinkSitePinInstTimingEdges.computeIfAbsent(e.sinkPin, k -> new ArrayList<>(1)).add(te);
            }
        }
        System.out.println("INFO: Versal timing graph: " + vertexSet().size() + " vertices, " + edgeSet().size()
                + " edges, " + launchClkToQ.size() + " launches, " + endpointCheck.size() + " endpoints");
        if (clockSkew) versalGraph = vg;
        orderedTimingVertices.clear();
        reversedOrderedTimingVertices.clear();
    }

    /**
     * Connects the launches to the super source (edge delay = clock-to-Q) and the endpoints to the
     * super sink (edge delay = setup check), so that the required-time normalisation sees the same
     * path delay the Versal slack analysis uses, without the clock arrivals.
     */
    @Override
    public void buildSuperGraphPaths() {
        if (superSource == null) {
            superSource = new TimingVertex("superSource");
            superSink = new TimingVertex("superSink");
        }
        superSource = safeAddVertex(superSource);
        superSink = safeAddVertex(superSink);
        for (Map.Entry<TimingVertex, Float> l : launchClkToQ.entrySet()) {
            TimingEdge e = new TimingEdge(this, superSource, l.getKey());
            e.setLogicDelay(l.getValue());
            if (safeAddEdge(superSource, l.getKey(), e)) setEdgeWeight(e, e.getDelay());
        }
        for (Map.Entry<TimingVertex, Float> s : endpointCheck.entrySet()) {
            TimingEdge e = new TimingEdge(this, s.getKey(), superSink);
            e.setLogicDelay(Math.max(0f, s.getValue()));
            if (safeAddEdge(s.getKey(), superSink, e)) setEdgeWeight(e, e.getDelay());
        }
        orderedTimingVertices.clear();
        reversedOrderedTimingVertices.clear();
    }

    /** More launch clock groups than this reaching a vertex: its endpoints get no pessimism credit (conservative). */
    private static final int MAX_GROUPS = 64;
    private static final int[] MANY = new int[0];

    /**
     * Folds the clock arrivals of the routed global clock nets into the super edges, once. With
     * {@code L} the launch clock at the max corner, {@code Q} the clock-to-Q, {@code C} the capture
     * clock at the min corner, {@code P} the pessimism removal (minus the inter-SLR compensation),
     * {@code S} the setup check and {@code T} the period, the report's setup slack is
     * {@code T + C + P - S - (L + Q + data)}. The edges become {@code L - L0 + Q} (super source to
     * launch) and {@code S + K - (C - L0) - P} (endpoint to super sink) with {@code L0} the earliest
     * launch clock and {@code K} the smallest offset keeping every sink edge non-negative, and the
     * timing requirement becomes {@code T + K}, so that the router's slack of every path equals the
     * report's (without the setup uncertainty).
     *
     * <p>The pessimism removal depends on the launch, which a vertex STA cannot carry per path; each
     * endpoint gets the smallest credit over the launch clock groups (clock-tree leaves,
     * {@link VersalClockArrivals#launchGroup}) that reach it, found with one topological pass, which
     * is conservative for every launch. Launches and endpoints whose clock pin is not on a routed
     * global clock net (the picoblaze test's local clock) keep zero skew.
     * @param clockModel the clock model to analyse the routed clock nets with
     * @return a one-line summary for the log
     */
    public String applyClockArrivals(VersalClockModel clockModel) {
        if (versalGraph == null) {
            throw new IllegalStateException(clockArrivalsApplied ? "clock arrivals already applied" : "graph built without clock skew");
        }
        long t0 = System.currentTimeMillis();
        VersalClockArrivals clocks = new VersalClockArrivals(versalGraph, model, clockModel);
        Map<TimingVertex, float[]> launchArrival = new HashMap<>(), captureArrival = new HashMap<>();
        Map<TimingVertex, SitePinInst> capturePin = new HashMap<>();
        Map<Object, Integer> groupIndex = new HashMap<>();
        List<SitePinInst> groupPin = new ArrayList<>();
        List<VersalTimingGraph.Vertex> groupVertex = new ArrayList<>();
        Map<TimingVertex, Integer> launchGroupOf = new HashMap<>();
        int unclockedLaunches = 0, unclockedEndpoints = 0;
        float minLaunch = Float.POSITIVE_INFINITY, maxLaunch = Float.NEGATIVE_INFINITY;
        float minCapture = Float.POSITIVE_INFINITY, maxCapture = Float.NEGATIVE_INFINITY;
        for (Map.Entry<TimingVertex, VersalTimingGraph.Vertex> e : clockedVertex.entrySet()) {
            TimingVertex tv = e.getKey();
            VersalTimingGraph.Vertex v = e.getValue();
            SitePinInst spi = clocks.clockSitePin(v);
            float[] arr = clocks.clockArrival(spi, v.cell);
            if (v.launch) {
                if (arr == null) unclockedLaunches++;
                else {
                    launchArrival.put(tv, arr);
                    minLaunch = Math.min(minLaunch, arr[VersalClockArrivals.SLOW_MAX]);
                    maxLaunch = Math.max(maxLaunch, arr[VersalClockArrivals.SLOW_MAX]);
                    Object g = clocks.launchGroup(spi);
                    Integer gi = groupIndex.get(g);
                    if (gi == null) {
                        gi = groupPin.size();
                        groupIndex.put(g, gi);
                        groupPin.add(spi);
                        groupVertex.add(v);
                    }
                    launchGroupOf.put(tv, gi);
                }
            }
            if (v.endpoint) {
                if (arr == null) unclockedEndpoints++;
                else {
                    captureArrival.put(tv, arr);
                    capturePin.put(tv, spi);
                    minCapture = Math.min(minCapture, arr[VersalClockArrivals.SLOW_MIN]);
                    maxCapture = Math.max(maxCapture, arr[VersalClockArrivals.SLOW_MIN]);
                }
            }
        }
        if (launchArrival.isEmpty() && captureArrival.isEmpty()) {
            versalGraph = null;
            clockedVertex = null;
            clockArrivalsApplied = true;
            return "WARNING: clock skew requested but no launch or endpoint is on a routed global clock net; skew not applied";
        }
        float l0 = launchArrival.isEmpty() ? minCapture : minLaunch;

        // launch clock groups reaching every vertex, in topological order
        if (orderedTimingVertices.isEmpty()) setOrderedTimingVertexLists();
        Map<TimingVertex, int[]> groups = new HashMap<>();
        for (TimingVertex v : orderedTimingVertices) {
            int[] set = null;
            Integer g = launchGroupOf.get(v);
            if (g != null) set = new int[] {g};
            for (TimingEdge e : incomingEdgesOf(v)) {
                int[] ps = groups.get(e.getSrc());
                if (ps != null) set = union(set, ps);
            }
            if (set != null) groups.put(v, set);
        }

        // per endpoint: capture clock and the smallest pessimism credit over its launch groups
        Map<TimingVertex, Float> sinkTerm = new HashMap<>();   // (C - L0) - P
        int creditFallback = 0, creditedEndpoints = 0;
        float k = 0f;
        for (Map.Entry<TimingVertex, Float> e : endpointCheck.entrySet()) {
            TimingVertex tv = e.getKey();
            float[] arr = captureArrival.get(tv);
            float captureMin = arr == null ? l0 : arr[VersalClockArrivals.SLOW_MIN];
            float credit = 0f;
            if (arr != null) {
                int[] set = groups.get(tv);
                if (set == MANY) creditFallback++;
                else if (set != null) {
                    SitePinInst cap = capturePin.get(tv);
                    VersalTimingGraph.Vertex v = clockedVertex.get(tv);
                    VersalClockModel.ClockTree tree = clocks.getClockTree(cap.getNet());
                    credit = Float.POSITIVE_INFINITY;
                    for (int g : set) {
                        SitePinInst lp = groupPin.get(g);
                        VersalTimingGraph.Vertex launch = groupVertex.get(g);
                        float cpr = clocks.pessimismOf(tree, launch, lp, cap, arr)[0][0];
                        float slr = VersalClockArrivals.crossesSlr(launch, v)
                                ? (captureMin - clocks.commonDelayMin(tree, lp, cap)[0]) * VersalSlackAnalysis.SLR_PRORATING : 0f;
                        credit = Math.min(credit, cpr - slr);
                    }
                    if (Float.isInfinite(credit)) credit = 0f;
                    else creditedEndpoints++;
                }
            }
            float term = (captureMin - l0) + credit;
            sinkTerm.put(tv, term);
            k = Math.max(k, term - Math.max(0f, e.getValue()));
        }
        groups = null;

        // rewrite the super edges (setLogicDelay updates the graph weight; the order is unchanged)
        launchClockTerm = new HashMap<>();
        sinkClockTerm = new HashMap<>();
        for (Map.Entry<TimingVertex, Float> l : launchClkToQ.entrySet()) {
            float[] arr = launchArrival.get(l.getKey());
            float term = arr == null ? 0f : arr[VersalClockArrivals.SLOW_MAX] - l0;
            launchClockTerm.put(l.getKey(), term);
            TimingEdge e = getEdge(superSource, l.getKey());
            if (e != null) e.setLogicDelay(term + l.getValue());
        }
        for (Map.Entry<TimingVertex, Float> s : endpointCheck.entrySet()) {
            float term = k - sinkTerm.get(s.getKey());
            sinkClockTerm.put(s.getKey(), term);
            TimingEdge e = getEdge(s.getKey(), superSink);
            if (e != null) e.setLogicDelay(Math.max(0f, s.getValue()) + term);
        }
        clockOffset = k;
        if (getTimingManager() != null) {
            getTimingManager().setTimingRequirementPs(getTimingManager().getTimingRequirementPs() + k);
        }
        versalGraph = null;
        clockedVertex = null;
        clockArrivalsApplied = true;
        return String.format("INFO: Clock arrivals applied to %d launches (%d unclocked) and %d endpoints (%d unclocked): "
                + "launch clock %.0f..%.0f ps, capture clock %.0f..%.0f ps (slow corner), %d launch clock groups, "
                + "%d endpoints with pessimism credit (%d over %d groups: none), requirement offset %.0f ps, %d ms",
                launchArrival.size(), unclockedLaunches, captureArrival.size(), unclockedEndpoints,
                minLaunch, maxLaunch, minCapture, maxCapture, groupPin.size(), creditedEndpoints, creditFallback, MAX_GROUPS, k,
                System.currentTimeMillis() - t0);
    }

    /** Union of two sorted group-index arrays; {@link #MANY} once the cap is exceeded. */
    private static int[] union(int[] a, int[] b) {
        if (a == null) return b;
        if (a == MANY || b == MANY) return MANY;
        if (Arrays.equals(a, b)) return a;
        int[] out = new int[a.length + b.length];
        int i = 0, j = 0, n = 0;
        while (i < a.length && j < b.length) {
            if (a[i] < b[j]) out[n++] = a[i++];
            else if (a[i] > b[j]) out[n++] = b[j++];
            else { out[n++] = a[i++]; j++; }
        }
        while (i < a.length) out[n++] = a[i++];
        while (j < b.length) out[n++] = b[j++];
        if (n > MAX_GROUPS) return MANY;
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    @Override
    public boolean hasClockSkew() {
        return clockArrivalsApplied && launchClockTerm != null;
    }

    @Override
    public float getClockOffset() {
        return clockOffset;
    }

    @Override
    public float getLaunchClockTerm(TimingVertex launch) {
        Float t = launchClockTerm == null ? null : launchClockTerm.get(launch);
        return t == null ? 0f : t;
    }

    @Override
    public float getSinkClockTerm(TimingVertex endpoint) {
        Float t = sinkClockTerm == null ? null : sinkClockTerm.get(endpoint);
        return t == null ? 0f : t;
    }

    /**
     * Recomputes the net edges of one net from its current route (PIPs) with the full Versal model:
     * the interconnect delay to every routed sink site pin, plus the intra-site terms.
     * @return the number of edges updated
     */
    @Override
    public int addNetDelayEdges(Net net) {
        if (net.getSource() == null) return 0;
        int updated = 0;
        for (Map.Entry<SitePinInst, VersalTimingModel.SinkDelay> e : model.calcNetDelays(net).entrySet()) {
            List<TimingEdge> edges = sinkSitePinInstTimingEdges.get(e.getKey());
            if (edges == null) continue;
            VersalTimingModel.SinkDelay sd = e.getValue();
            if (!sd.routed) continue;
            float intra = sd.driverIntraSite[0] + sd.sinkIntraSite[0];
            for (TimingEdge te : edges) {
                te.setIntraSiteDelay(intra);
                te.setRouteDelay(sd.interconnect[0]);
                updated++;
            }
        }
        return updated;
    }

    /**
     * Interconnect delay (ps) at every node of a route tree given explicitly, for the router's
     * per-iteration refresh of the connection delays (the tree is made of the connections' nodes,
     * not of the net's PIPs, which RWRoute writes only at the end).
     */
    public Map<Node, float[]> calcNodeArrivals(List<Node> roots, Map<Node, List<Node>> children, Map<Node, Node> parentOf) {
        return model.calcNodeArrivalsAllCorners(roots, children, parentOf, null);
    }

    @Override
    public void setTimingEdgesOfConnections(List<Connection> connections) {
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            List<TimingEdge> timingEdges = sinkSitePinInstTimingEdges.get(connection.getSink());
            if (timingEdges == null) {
                // a sink the model has no vertex for (a cell type without a delay section, or a pin
                // without a physical mapping): the connection is routed without criticality
                if (connectionsWithoutEdges < 5) {
                    System.out.println("WARNING: no timing edges for connection " + connection.getSource() + " -> " + connection.getSink()
                            + " (net " + connection.getNet().getName() + ")");
                }
                connectionsWithoutEdges++;
                connection.setTimingEdges(Collections.emptyList());
                continue;
            }
            connection.setTimingEdges(timingEdges);
            for (TimingEdge edge : timingEdges) {
                timingEdgeConnectionMap.put(edge, connection);
            }
        }
        if (connectionsWithoutEdges > 0) {
            System.out.println("WARNING: " + connectionsWithoutEdges + " connections have no timing edges (sinks not in the Versal timing graph)");
        }
    }
}
