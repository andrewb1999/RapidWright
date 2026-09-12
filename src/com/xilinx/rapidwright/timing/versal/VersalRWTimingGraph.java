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
 */
public class VersalRWTimingGraph extends TimingGraph {

    private VersalTimingModel model;
    /** launch vertex -> clock-to-output delay (ps) */
    private final Map<TimingVertex, Float> launchClkToQ = new LinkedHashMap<>();
    /** endpoint vertex -> setup requirement (ps) */
    private final Map<TimingVertex, Float> endpointCheck = new LinkedHashMap<>();
    private int connectionsWithoutEdges = 0;

    public VersalRWTimingGraph(Design design, RuntimeTrackerTree timer) {
        super(design, timer, null, null);
    }

    public VersalTimingModel getModel() {
        return model;
    }

    /** A slow-max-only Versal timing model (the corner RWRoute's setup-driven routing works at). */
    public static VersalTimingModel createRouterModel(Design design) {
        VersalCorner corner = VersalCorner.SLOW_MAX;
        return new VersalTimingModel(design.getDevice(), corner, new VersalDelayTerms(corner),
                DelayModelBuilder.getDelayModel("versal", corner.getSuffix()));
    }

    @Override
    public void build(boolean isPartialRouting, Collection<Net> targetNets) {
        Design design = getDesign();
        if (model == null) {
            model = createRouterModel(design);
        }
        VersalTimingGraph vg = new VersalTimingGraph(design, model, true);
        vg.build();

        Map<VersalTimingGraph.Vertex, TimingVertex> map = new HashMap<>();
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
