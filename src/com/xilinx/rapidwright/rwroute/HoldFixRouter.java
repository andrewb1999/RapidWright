/*
 *
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt
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
package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.timing.versal.VersalClockModel;
import com.xilinx.rapidwright.timing.versal.VersalCorner;
import com.xilinx.rapidwright.timing.versal.VersalClockArrivals;
import com.xilinx.rapidwright.timing.versal.VersalSlackAnalysis;
import com.xilinx.rapidwright.timing.versal.VersalTimingGraph;
import com.xilinx.rapidwright.timing.versal.VersalTimingModel;
import com.xilinx.rapidwright.timing.versal.VersalTimingReport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A {@link PartialRouter} for repairing hold-time violations by re-routing the offending
 * connections with more delay. Two mechanisms, usable together:
 * <ul>
 * <li>Node-type exclusion (the original mechanism): the given intent codes (by default the quad
 * and long wires of the series) are not used, so every re-routed connection takes slower wires.</li>
 * <li>Delay budgets ({@link #setDelayBudget}): a connection whose sink has a budget is routed
 * towards a target delay with the Routing Cost Valleys cost of Fung, Betz and Chow (TCAD 2008):
 * linear cost with the short-path criticality below the target, quadratic penalties below the
 * minimum and above the maximum budget, on top of RWRoute's congestion and wirelength cost. The
 * search keeps the delay of the partial path on its queue entries and re-expands a node whenever a
 * path with a lower total cost reaches it (the fastest path to a node is the wrong one here), so
 * a budgeted connection uses its own queue; every other connection is routed as by
 * {@link PartialRouter}. Budgets are in the router's delay units: the sum of the per-node delays of
 * the timing-driven routing graph ({@code RouteNode.getDelay()} plus
 * {@link DelayEstimatorBase#getExtraDelay}) along the route, the same sum
 * {@link #routeDelay(Net, SitePinInst)} computes for an existing route; on Versal these are the
 * slow-max marginals of {@code VersalDelayEstimator}, on UltraScale+ the delay estimator's.</li>
 * </ul>
 * The routing graph is always the timing-driven one so that node delays are available; no timing
 * manager is built (the configuration stays non-timing-driven), the caller supplies the budgets
 * from its own timing analysis.
 */
public class HoldFixRouter extends PartialRouter {

    static List<IntentCode> defaultDisallowedNodeTypesVersal = new ArrayList<>();
    static {
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VQUAD);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HQUAD);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VLONG7);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_VLONG12);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HLONG6);
        defaultDisallowedNodeTypesVersal.add(IntentCode.NODE_HLONG10);
    }

    static List<IntentCode> defaultDisallowedNodeTypesUltraScale = new ArrayList<>();
    static {
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_VQUAD);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_HQUAD);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_VLONG);
        defaultDisallowedNodeTypesUltraScale.add(IntentCode.NODE_HLONG);
    }

    public static List<IntentCode> defaultDisallowedNodeTypes(Design design) {
        if (design.getSeries().equals(Series.Versal)) {
            return defaultDisallowedNodeTypesVersal;
        } else if (design.getSeries().equals(Series.UltraScale) || design.getSeries().equals(Series.UltraScalePlus)) {
            return defaultDisallowedNodeTypesUltraScale;
        }
        return new ArrayList<>();
    }

    private final List<IntentCode> disallowedNodeTypes;

    /** A minimum / maximum delay budget for one connection (router units, ps). */
    public static class DelayBudget {
        public final float min, max;
        /** the delay of the connection before the re-route, the lower bound the short-path criticality is measured from */
        public final float lowerBound;
        public DelayBudget(float min, float max, float lowerBound) {
            this.min = min;
            this.max = max;
            this.lowerBound = lowerBound;
        }
        /** the delay the search aims at: just above the minimum, within the window */
        float target() {
            return Math.min(0.5f * (min + max), min + TARGET_ABOVE_MIN_PS);
        }
    }

    /** The target sits this far above the minimum budget when the window allows (RCV: 100 ps). */
    public static final float TARGET_ABOVE_MIN_PS = 100f;
    /** Quadratic penalties are normalised by this (RCV: 100 ps, the smallest delay step the fabric offers). */
    public static final float QUADRATIC_NORM_PS = 100f;
    /** Exponent of the short-path criticality (RCV: 0.5). */
    public static final float SHORT_PATH_EXPONENT = 0.5f;
    /** Long-path criticality of a budgeted connection: its (small) wish to stay fast above the target (RCV's floor: 0.1). */
    private float longPathCriticality = 0.1f;
    /** Scale of the whole delay cost against RWRoute's congestion and wirelength cost. */
    private float delayCostWeight = 1.0f;
    /** Bounding-box growth per 100 ps of delay to add, in tiles each way (a detour spends two hops per tile of excursion). */
    private float tilesPer100ps = 1.0f;
    /** Extra bounding-box tiles for every budgeted connection. */
    private int boundingBoxMargin = 2;
    /** Re-route attempts of a budgeted connection that came out below its minimum. */
    private int maxRetries = 2;
    /** A connection this far (ps) or more below its minimum after routing is retried with a heavier delay cost. */
    private float retryToleranceUnder = 10f;
    /** Whether SLR-crossing connections take budgets too (the detour then sits on either side of the SLL). */
    private boolean budgetCrossSlr = true;
    /** A partial path may exceed the maximum budget by this much (ps) before the search drops it. */
    private float capTolerancePs = 20f;
    /** A budgeted search that pops this many entries without reaching the sink gives up (the window is empty or unreachable). */
    private int maxPopsPerSearch = 100000;
    /** Delay per tile (ps) the budgeted search expects for the rest of the way to the sink (single/double wires). */
    private float holdPsPerTile = 35f;
    /** What one SLL crossing costs in the estimate (Versal SLL_INPUT marginal). */
    public static final float SLL_DELAY_PS = 500f;
    /** How many vertices back from an endpoint {@link #fixHoldByDetour} walks for short paths, and how many it budgets per endpoint. */
    public static final int SHORT_PATH_DEPTH = 6, SHORT_PATHS_PER_ENDPOINT = 16;
    /** Hold slack (ps, hold corner) a budget aims for above the margin, so the conversion's undershoot still lands above it. */
    public static final float BUDGET_OVERSHOOT_PS = 30f;
    /** The full model's slow-max net delay over the router's marginal sum for the same route (32x8 median 1.10): converts a model delay into a lower bound in router units. */
    public static final float MODEL_OVER_ROUTER_DELAY = 1.10f;

    /**
     * The delay window for a connection that must gain {@code deficitPs} of hold slack (hold corner, the
     * overshoot included by the caller) without spending more than {@code roomPs} of setup slack: the
     * deficit converted to router units by the connection's slow-max over min-corner {@code ratio}, the
     * room by the model-over-router safety factor. Null when the room cannot hold the deficit.
     */
    public static DelayBudget budgetFor(float lowerBound, float deficitPs, float roomPs, float ratio, float maxExtraPs) {
        if (roomPs < deficitPs) return null;
        float extra = deficitPs * ratio;
        float maxExtra = Math.min(roomPs / SETUP_ROOM_SAFETY, extra + maxExtraPs);
        if (maxExtra < extra) return null;
        return new DelayBudget(lowerBound + extra, lowerBound + maxExtra, lowerBound);
    }
    /** The full model's setup delay of a detour against the router's marginal sum (32x8: median 1.10, p90 1.37). */
    public static final float SETUP_ROOM_SAFETY = 1.15f;
    /** budgeted connections whose window held no route and which fell back to the plain search */
    private int fallbacks = 0;

    private final Map<SitePinInst, DelayBudget> budgetOfSink = new HashMap<>();
    private final Map<Connection, DelayBudget> budgetOfConnection = new HashMap<>();
    /** outcome per budgeted connection after routing: achieved delay (router units), or NaN if unrouted */
    private final Map<Connection, Float> achievedDelay = new HashMap<>();

    // --- state of the budgeted search (one connection at a time) ---
    private static class Entry {
        final RouteNode node;
        final float total;
        final float upstreamDelay;
        final Entry prev;
        Entry(RouteNode node, float total, float upstreamDelay, Entry prev) {
            this.node = node; this.total = total; this.upstreamDelay = upstreamDelay; this.prev = prev;
        }
    }
    private final PriorityQueue<Entry> holdQueue = new PriorityQueue<>((a, b) -> Float.compare(a.total, b.total));
    private final Map<RouteNode, Entry> bestEntry = new HashMap<>();
    private Entry currentEntry;
    /** the cheapest entry that reached a target so far in the current search */
    private Entry bestSinkEntry;
    private long capPrunes, loopRejects;
    private int fallbackDiagnostics = 0;
    /** budgeted searches that ran out of pops and took the best route that had reached the sink */
    private int partialSinks = 0;
    private DelayBudget currentBudget;
    private float currentWeight;
    private int holdSequence = 0;
    private long holdNodesPopped = 0, holdNodesPushed = 0, holdStaleSkipped = 0;

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve,
                         List<IntentCode> disallowedNodeTypes) {
        super(design, config, pinsToRoute, softPreserve);
        this.disallowedNodeTypes = disallowedNodeTypes;
    }

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        this(design, config, pinsToRoute, softPreserve, defaultDisallowedNodeTypes(design));
    }

    public HoldFixRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        this(design, config, pinsToRoute, false);
    }

    // --- budgets ---

    /**
     * Gives the connection ending at a sink pin a delay window (router units, see the class
     * comment). Call before {@link #initialize()} or before {@link #route()}.
     * @param lowerBound the connection's delay before the re-route (its fastest route, in practice)
     */
    public void setDelayBudget(SitePinInst sink, float minDelay, float maxDelay, float lowerBound) {
        if (maxDelay < minDelay) throw new IllegalArgumentException("max budget " + maxDelay + " < min budget " + minDelay + " for " + sink);
        budgetOfSink.put(sink, new DelayBudget(minDelay, maxDelay, lowerBound));
    }

    public void setDelayBudget(SitePinInst sink, float minDelay, float maxDelay) {
        setDelayBudget(sink, minDelay, maxDelay, 0f);
    }

    public Map<SitePinInst, DelayBudget> getDelayBudgets() {
        return budgetOfSink;
    }

    /** Diagnostic: the state of the connection(s) on a sink after {@link #route()}. */
    public String describeSink(SitePinInst sink) {
        StringBuilder sb = new StringBuilder();
        for (Connection c : indirectConnections) {
            if (c.getSink() != sink) continue;
            sb.append(String.format("indirect connection routed %b direct %b crossSLR %b altSinks %d sinkRnode %s rnodes %d budget %b; ",
                    c.isRouted(), c.isDirect(), c.isCrossSLR(), c.getAltSinkRnodes().size(), c.getSinkRnode(), c.getRnodes().size(), budgetOfConnection.containsKey(c)));
        }
        if (sb.length() == 0) sb.append("no connection on this sink; ");
        sb.append("pin routed ").append(sink.isRouted()).append(" node ").append(sink.getConnectedNode());
        Net net = sink.getNet();
        Node n = RouterHelper.projectInputPinToINTNode(sink);
        sb.append("; net sinks:");
        for (SitePinInst q : net.getSinkPins()) sb.append(' ').append(q).append('(').append(q.isRouted() ? "routed" : "unrouted").append(',').append(RouterHelper.projectInputPinToINTNode(q)).append(')');
        sb.append("; PIPs at ").append(n).append(':');
        for (PIP pip : net.getPIPs()) if (pip.getStartNode().equals(n) || pip.getEndNode().equals(n)) sb.append(' ').append(pip);
        return sb.toString();
    }

    /** The delay (router units) the last {@link #route()} achieved for a budgeted sink; NaN if it was not routed, null if it had no budget. */
    public Float getAchievedDelay(SitePinInst sink) {
        for (Map.Entry<Connection, Float> e : achievedDelay.entrySet()) {
            if (e.getKey().getSink() == sink) return e.getValue();
        }
        return null;
    }

    public void setLongPathCriticality(float c) { longPathCriticality = c; }
    public void setDelayCostWeight(float w) { delayCostWeight = w; }
    public void setTilesPer100ps(float t) { tilesPer100ps = t; }
    public void setBoundingBoxMargin(int tiles) { boundingBoxMargin = tiles; }
    public void setMaxRetries(int n) { maxRetries = n; }
    public void setBudgetCrossSlr(boolean b) { budgetCrossSlr = b; }
    public void setMaxPopsPerSearch(int n) { maxPopsPerSearch = n; }
    public void setHoldPsPerTile(float ps) { holdPsPerTile = ps; }

    /**
     * The delay of an existing route to a sink in the router's units (the sum of the timing-driven
     * graph's node delays and the long-after-long correction along the net's PIPs from the source),
     * or NaN when the sink is not reached by the net's PIPs. Usable before {@link #initialize()}.
     */
    public float routeDelay(Net net, SitePinInst sink) {
        DelayEstimatorBase<?> estimator = getDelayEstimator();
        Map<Node, Node> parentOf = new HashMap<>();
        for (PIP p : net.getPIPs()) {
            Node s = p.isReversed() ? p.getEndNode() : p.getStartNode();
            Node e = p.isReversed() ? p.getStartNode() : p.getEndNode();
            parentOf.putIfAbsent(e, s);
        }
        Node n = sink.getConnectedNode();
        if (n == null || !parentOf.containsKey(n)) return Float.NaN;
        float delay = 0;
        Set<Node> seen = new HashSet<>();
        while (n != null && seen.add(n)) {
            Node parent = parentOf.get(n);
            delay += RouterHelper.computeNodeDelay(estimator, n);
            if (parent != null) delay += DelayEstimatorBase.getExtraDelay(n, DelayEstimatorBase.isLong(parent));
            n = parent;
        }
        return delay;
    }

    private DelayEstimatorBase<?> delayEstimator;
    private DelayEstimatorBase<?> getDelayEstimator() {
        if (delayEstimator == null) delayEstimator = RouterHelper.createDelayEstimator(design, config);
        return delayEstimator;
    }

    // --- graph ---

    /**
     * PartialRouter turns every connection of a net that has a pin to route into an ordinary, rippable
     * connection, so detouring one sink lets congestion from other nets' detours reroute the net's other
     * sinks or leave them with no route at all (32x8: 76 sibling sinks rerouted in one round, some stranded
     * and later re-hung off a route-through's output). Keep those recovered connections' nodes preserved
     * for the net: the partial graph still lets this net expand along its own existing arcs and branch
     * off them, and other nets are kept out.
     */
    @Override
    protected void addNetConnectionToRoutingTargets(Net net) {
        List<SitePinInst> requested = netToPins.get(net);
        Set<SitePinInst> toRoute = requested == null ? null : new HashSet<>(requested);   // super replaces the list with every sink
        super.addNetConnectionToRoutingTargets(net);
        if (toRoute == null) return;
        NetWrapper netWrapper = nets.get(net);
        if (netWrapper == null) return;
        for (Connection c : netWrapper.getConnections()) {
            if (c.isDirect() || !c.isRouted() || toRoute.contains(c.getSink())) continue;
            for (RouteNode rnode : c.getRnodes()) routingGraph.preserve(rnode, net);
        }
        // a deferred sink is left for a later pass: its connection is marked routed with no route, so this
        // pass neither routes it nor reports it unroutable
        for (Connection c : netWrapper.getConnections()) {
            if (!c.isDirect() && !c.isRouted() && deferredSinks.contains(c.getSink())) c.setRouted(true);
        }
    }

    private final Set<SitePinInst> deferredSinks = new HashSet<>();

    /**
     * Sinks of the target nets this router leaves unrouted (their pins are not targets, and their
     * connections are not routed plainly beside the budgeted ones): a sibling that shares a bounce node
     * with a budgeted sink would otherwise be routed to that node through a shorter driver in the same
     * pass and the route fixer would keep that driver, discarding the budgeted detour.
     */
    public void setDeferredSinks(Collection<SitePinInst> sinks) { deferredSinks.clear(); deferredSinks.addAll(sinks); }

    protected static class RouteNodeGraphHoldFix extends RouteNodeGraphPartial {
        private final List<IntentCode> disallowedNodeTypes;

        public RouteNodeGraphHoldFix(Design design, RWRouteConfig config, List<IntentCode> disallowedNodeTypes) {
            super(design, config);
            this.disallowedNodeTypes = disallowedNodeTypes;
        }

        @Override
        protected boolean isExcluded(RouteNode parent, Node child) {
            IntentCode ic = child.getIntentCode();
            if (disallowedNodeTypes.contains(ic)) {
                return true;
            }
            return super.isExcluded(parent, child);
        }
    }

    protected static class RouteNodeGraphHoldFixTimingDriven extends RouteNodeGraphPartialTimingDriven {
        private final List<IntentCode> disallowedNodeTypes;

        public RouteNodeGraphHoldFixTimingDriven(Design design,
                                                 RWRouteConfig config,
                                                 DelayEstimatorBase<InterconnectInfo> delayEstimator,
                                                 List<IntentCode> disallowedNodeTypes) {
            super(design, config, delayEstimator);
            this.disallowedNodeTypes = disallowedNodeTypes;
        }

        @Override
        protected boolean isExcluded(RouteNode parent, Node child) {
            IntentCode ic = child.getIntentCode();
            if (disallowedNodeTypes.contains(ic)) {
                return true;
            }
            return super.isExcluded(parent, child);
        }
    }

    /**
     * The PIP-overlap audit over the nets this router routed only. RWRoute's audit rebuilds a map of every
     * PIP of every net in the design after each call, which on a large design costs more than the routing
     * itself (32x8: about 10 s and several GB per call for a handful of detours); the partial graph keeps
     * every other net's nodes preserved, so overlaps can only arise among the routed nets.
     */
    @Override
    protected void checkPIPsUsage() {
        Map<PIP, Net> owner = new HashMap<>();
        int overlaps = 0;
        for (Net net : nets.keySet()) {
            for (PIP pip : net.getPIPs()) {
                Net other = owner.put(pip, net);
                if (other != null && other != net) {
                    if (overlaps++ < 10) System.out.println("pip " + pip + " users = [" + other.getName() + ", " + net.getName() + "]");
                }
            }
        }
        if (overlaps > 0) System.err.println("ERROR: PIPs overused error: " + overlaps);
        else System.out.println("\nINFO: No PIP overlaps among the " + nets.size() + " routed nets\n");
    }

    @Override
    protected RouteNodeGraph createRouteNodeGraph() {
        // always delay-aware: the budgets need node delays (the series' estimator; on Versal the per-node
        // marginals), the timing manager is not built unless the configuration is timing-driven
        DelayEstimatorBase<InterconnectInfo> estimator = RouterHelper.createDelayEstimator(design, config);
        delayEstimator = estimator;
        return new RouteNodeGraphHoldFixTimingDriven(design, config, estimator, disallowedNodeTypes);
    }

    // --- routing ---

    @Override
    public void initialize() {
        super.initialize();
        assignBudgets();
    }

    /** Maps the sink budgets onto the connections and gives them room to detour. */
    private void assignBudgets() {
        budgetOfConnection.clear();
        achievedDelay.clear();
        if (budgetOfSink.isEmpty()) return;
        int matched = 0;
        for (Connection c : indirectConnections) {
            DelayBudget b = budgetOfSink.get(c.getSink());
            if (b == null) continue;
            budgetOfConnection.put(c, b);
            matched++;
            float add = Math.max(0f, b.min - b.lowerBound);
            int grow = boundingBoxMargin + (int) Math.ceil(add / 100f * tilesPer100ps);
            c.enlargeBoundingBox(grow, grow);
        }
        if (matched < budgetOfSink.size()) {
            System.out.println("WARNING: " + (budgetOfSink.size() - matched) + " of " + budgetOfSink.size()
                    + " delay budgets are on sinks without an indirect connection to route (direct, already routed, or not a routing target)");
        }
        System.out.println("INFO: HoldFixRouter: " + matched + " connections with delay budgets");
    }

    @Override
    protected void routeIndirectConnection(Connection connection) {
        DelayBudget budget = budgetOfConnection.get(connection);
        if (budget == null || (connection.isCrossSLR() && !budgetCrossSlr)) {
            super.routeIndirectConnection(connection);
            return;
        }
        float weight = delayCostWeight;
        for (int attempt = 0; ; attempt++) {
            routeBudgetedConnection(connection, budget, weight);
            if (!connection.isRouted()) {
                // no route inside the window (the cap, congestion, or the box): the plain search, so the
                // connection is at least routed; its endpoints keep their hold violation
                fallbacks++;
                if (fallbackDiagnostics++ < 25) {
                    ConnectionState st = getConnectionState();
                    System.out.printf("[HoldFixRouter] fallback %s: crossSLR %b, budget [%.0f, %.0f] from %.0f, %d pops, %d cap prunes, %d loop rejects, sink seen %b, box x %d..%d y %d..%d, sink (%d,%d) source (%d,%d)%n",
                            connection.getSink(), connection.isCrossSLR(), budget.min, budget.max, budget.lowerBound, st.nodesPopped, capPrunes, loopRejects,
                            bestSinkEntry != null, connection.getXMinBB(), connection.getXMaxBB(), connection.getYMinBB(), connection.getYMaxBB(),
                            connection.getSinkRnode().getBeginTileXCoordinate(), connection.getSinkRnode().getBeginTileYCoordinate(),
                            connection.getSourceRnode().getEndTileXCoordinate(), connection.getSourceRnode().getEndTileYCoordinate());
                }
                super.routeIndirectConnection(connection);
                achievedDelay.put(connection, connection.isRouted() ? routeDelayOf(connection) : Float.NaN);
                return;
            }
            float achieved = routeDelayOf(connection);
            achievedDelay.put(connection, achieved);
            if (achieved >= budget.min - retryToleranceUnder || attempt >= maxRetries) {
                if (achieved < budget.min - retryToleranceUnder) {
                    System.out.printf("WARNING: HoldFixRouter: %s routed at %.0f ps, below its minimum budget %.0f ps after %d attempts%n",
                            connection.getSink(), achieved, budget.min, attempt + 1);
                }
                return;
            }
            // came out too fast: press harder and widen the box
            weight *= 4f;
            connection.enlargeBoundingBox(boundingBoxMargin, boundingBoxMargin);
        }
    }

    /** The delay (router units) of a connection's current route, the way the budgeted search counts it. */
    private static float routeDelayOf(Connection connection) {
        List<RouteNode> rnodes = connection.getRnodes();   // sink first
        if (rnodes.isEmpty()) return Float.NaN;
        float delay = rnodes.get(rnodes.size() - 1).getDelay();
        for (int i = rnodes.size() - 2; i >= 0; i--) {
            RouteNode child = rnodes.get(i), parent = rnodes.get(i + 1);
            delay += child.getDelay() + DelayEstimatorBase.getExtraDelay(child, DelayEstimatorBase.isLong(parent));
        }
        return delay;
    }

    /**
     * Routes one connection towards its delay window: RWRoute's search with its own queue of
     * (node, path) entries ordered by total cost, re-expanding a node when a cheaper path reaches it.
     */
    private void routeBudgetedConnection(Connection connection, DelayBudget budget, float weight) {
        ConnectionState state = getConnectionState();
        state.connection = connection;
        // a sequence no ordinary connection uses: nodes are never marked visited here, so the base
        // class's visited check never fires for this search
        state.sequence = --holdSequence;
        state.rnodeCostWeight = 1f;   // criticality 0: full congestion and wirelength cost
        state.shareWeight = 1f;
        state.rnodeWLWeight = 1f - config.getWirelengthWeight();
        state.estWlWeight = config.getWirelengthWeight();
        state.dlyWeight = 0f;
        state.estDlyWeight = 0f;
        state.nodesPopped = 0;
        state.earlyTermination = false;

        currentBudget = budget;
        currentWeight = weight;
        currentEntry = null;
        holdQueue.clear();
        bestEntry.clear();
        capPrunes = 0;
        loopRejects = 0;

        prepareRouteConnection(state);   // rips up, marks the targets, pushes the source (through push())

        Entry sinkEntry = null;
        bestSinkEntry = null;
        Entry e;
        while ((e = holdQueue.poll()) != null) {
            if (bestEntry.get(e.node) != e) { holdStaleSkipped++; continue; }   // a cheaper path reached this node since
            if (state.nodesPopped >= maxPopsPerSearch) break;
            state.nodesPopped++;
            if (e.node.isTarget()) { sinkEntry = e; break; }
            currentEntry = e;
            // the base class's early termination (set when a child is the uncongested target) is not
            // wanted here: the target must be popped in cost order like any node
            state.earlyTermination = false;
            state.queue.clear();
            exploreAndExpand(state, e.node);
        }
        if (sinkEntry == null && bestSinkEntry != null && bestEntry.get(bestSinkEntry.node) == bestSinkEntry) {
            // out of pops before the sink came to the top of the queue: the cheapest route that did reach it
            // (below its minimum, or the search would have ended on it) still improves the hold slack
            sinkEntry = bestSinkEntry;
            partialSinks++;
        }
        holdNodesPopped += state.nodesPopped;
        holdNodesPushed += state.nodesPopped + holdQueue.size();
        holdQueue.clear();
        state.queue.clear();
        state.earlyTermination = false;
        currentBudget = null;
        currentEntry = null;

        if (sinkEntry != null) {
            // the path is the entry chain, not the nodes' prev pointers (a node may have been re-pushed since)
            for (Entry x = sinkEntry; x.prev != null; x = x.prev) {
                x.node.setPrev(x.prev.node);   // the source keeps its null prev
            }
            finishRouteConnection(connection, sinkEntry.node);
            if (!connection.isRouted()) {
                List<RouteNode> rnodes = connection.getRnodes();
                throw new RuntimeException("ERROR: Unable to save routing for connection " + connection + "\n" +
                        "       Backtracking terminated at " + rnodes.get(rnodes.size() - 1));
            }
        } else {
            connection.resetRoute();
            connection.setRouted(false);
        }
        bestEntry.clear();
        // reset the nodes marked as this connection's targets (the base class does this at the end of its own loop)
        for (RouteNode target : state.targets) {
            target.clearTarget();
        }
        state.targets.clear();
    }

    /**
     * For a budgeted connection: replaces the base class's push (which marks the node visited and
     * queues the node itself) by a queue entry carrying the path's delay, priced with the cost valley.
     */
    @Override
    protected void push(ConnectionState state, RouteNode childRnode, float newPartialPathCost, float newTotalPathCost, boolean lookahead) {
        if (currentBudget == null) {
            super.push(state, childRnode, newPartialPathCost, newTotalPathCost, lookahead);
            return;
        }
        Connection connection = state.connection;
        if (state.earlyTermination && childRnode.isTarget()) {
            // exploreAndExpand asserts that the base queue then holds exactly the target; it is cleared
            // again before the next expansion and never polled
            state.queue.clear();
            state.queue.add(childRnode);
        }
        Entry parent = currentEntry;
        boolean isSource = parent == null;
        float upstreamDelay;
        if (isSource) {
            upstreamDelay = childRnode.getDelay();
        } else {
            // no loops: the entry chain becomes the route
            for (Entry x = parent; x != null; x = x.prev) {
                if (x.node == childRnode) { loopRejects++; restorePrev(childRnode); return; }
            }
            upstreamDelay = parent.upstreamDelay + childRnode.getDelay()
                    + DelayEstimatorBase.getExtraDelay(childRnode, DelayEstimatorBase.isLong(parent.node));
        }
        // the maximum budget is a hard cap: a path already past it is not extended (setup is at stake)
        if (upstreamDelay > currentBudget.max + capTolerancePs) { capPrunes++; restorePrev(childRnode); return; }
        // expected total delay of a route through this node: what is behind plus the estimate to the sink
        RouteNode sinkRnode = connection.getSinkRnode();
        int deltaX = Math.abs(childRnode.getEndTileXCoordinate() - sinkRnode.getBeginTileXCoordinate());
        int deltaY = Math.abs(childRnode.getEndTileYCoordinate() - sinkRnode.getBeginTileYCoordinate());
        // the remaining delay to the sink: not the base search's optimistic long-wire rate (a detour near the
        // sink runs on singles and doubles, 50-80 ps a hop), an accurate estimate keeps the valley honest;
        // an SLR still to cross is one SLL hop for its tiles
        float remaining;
        if (connection.isCrossSLR() && childRnode.getSLRIndex(routingGraph) != sinkRnode.getSLRIndex(routingGraph)) {
            remaining = SLL_DELAY_PS + (deltaX + Math.max(0, deltaY - routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES)) * holdPsPerTile;
        } else {
            remaining = (deltaX + deltaY) * holdPsPerTile;
        }
        float expected = upstreamDelay + remaining;
        float total = newTotalPathCost + currentWeight * valleyCost(currentBudget, expected, upstreamDelay);

        Entry best = bestEntry.get(childRnode);
        if (best != null && total >= best.total) { restorePrev(childRnode); return; }
        childRnode.setLowerBoundTotalPathCost(total);
        childRnode.setUpstreamPathCost(newPartialPathCost);
        Entry entry = new Entry(childRnode, total, upstreamDelay, parent);
        bestEntry.put(childRnode, entry);
        holdQueue.add(entry);
        if (childRnode.isTarget() && (bestSinkEntry == null || total < bestSinkEntry.total)) bestSinkEntry = entry;
    }

    /** evaluateCostAndPush sets the child's prev before costing; a rejected push puts the best path's parent back. */
    private void restorePrev(RouteNode child) {
        Entry best = bestEntry.get(child);
        if (best != null && best.prev != null) child.setPrev(best.prev.node);
    }

    /**
     * The delay part of the cost (RCV eq. 14, in units of 100 ps): linear towards the target from
     * both sides and quadratic below the minimum on the expected total delay; quadratic above the
     * maximum on the known upstream delay only (the estimate to the sink is rough over long
     * distances, and the hard cap bounds the upstream delay anyway).
     */
    float valleyCost(DelayBudget b, float expected, float upstream) {
        float target = b.target();
        float shortCrit = target > 0 ? (float) Math.pow(Math.max(0f, target - b.lowerBound) / target, SHORT_PATH_EXPONENT) : 0f;
        float longCrit = longPathCriticality;
        float cost = longCrit * expected;
        cost += (shortCrit + longCrit) * Math.max(0f, target - expected);
        float over = Math.max(0f, upstream - b.max);
        float under = Math.max(0f, b.min - expected);
        cost += over * over / QUADRATIC_NORM_PS + under * under / QUADRATIC_NORM_PS;
        return cost / 100f;
    }

    /** Statistics of the budgeted searches of the last {@link #route()}: nodes popped, nodes pushed, stale entries skipped, fallbacks to the plain search. */
    public long[] getBudgetedSearchStats() {
        return new long[] {holdNodesPopped, holdNodesPushed, holdStaleSkipped, fallbacks, partialSinks};
    }

    /** Budgeted connections whose route ended below the minimum budget (by more than the tolerance) or unrouted. */
    public List<Connection> getUnmetBudgets() {
        List<Connection> out = new ArrayList<>();
        for (Map.Entry<Connection, Float> e : achievedDelay.entrySet()) {
            DelayBudget b = budgetOfConnection.get(e.getKey());
            if (Float.isNaN(e.getValue()) || e.getValue() < b.min - retryToleranceUnder) out.add(e.getKey());
        }
        return out;
    }

    /** Per budgeted connection: sink, budget min/max, lower bound and achieved delay. */
    public String describeBudgets() {
        StringBuilder sb = new StringBuilder();
        List<Connection> cs = new ArrayList<>(budgetOfConnection.keySet());
        cs.sort((a, b) -> a.getSink().toString().compareTo(b.getSink().toString()));
        for (Connection c : cs) {
            DelayBudget b = budgetOfConnection.get(c);
            Float a = achievedDelay.get(c);
            sb.append(String.format("%s: before %.0f, budget [%.0f, %.0f], routed %s%n", c.getSink(), b.lowerBound, b.min, b.max,
                    a == null ? "-" : Float.isNaN(a) ? "unrouted" : String.format("%.0f", a)));
        }
        return sb.toString();
    }

    List<IntentCode> getDisallowedNodeTypes() {
        return Collections.unmodifiableList(disallowedNodeTypes);
    }

    // --- a hold-repair pass on a routed Versal design, driven by the Versal timing model ---

    /** What {@link #fixHoldByDetour} did. */
    public static class Outcome {
        public float whsBefore, whsAfter, wnsBefore, wnsAfter;
        public int violatingBefore, violatingAfter, belowMarginBefore, belowMarginAfter;
        public int endpointsTargeted, pathsBudgeted, sinksBudgeted, skippedNoNetEdge, skippedCrossing, skippedSetupRoom, skippedNoRouteDelay, routed, budgetMet, fallbacks;
        /** endpoints (any, not only targets) whose setup slack the detours pushed under the floor, before and after the revert */
        public int setupPushedUnderFloor, sinksNotSlower;
        /** budgeted sinks that no search could route again, left unrouted (reported; expected 0) */
        public int unroutedSinks;
        public long pipsBefore, pipsAfter;
        public long analysisMs, routeMs;
        /** the analysis the round used, current for the design after it */
        public VersalSlackAnalysis analysis;
        @Override
        public String toString() {
            return String.format("hold: WHS %.0f -> %.0f ps, endpoints below 0: %d -> %d, below margin: %d -> %d; setup: WNS %.0f -> %.0f ps; "
                    + "%d endpoints targeted, %d short paths -> %d sinks budgeted (%d endpoints without a net edge, %d crossing paths left to the ladder, %d paths without setup room, %d sinks without a route delay); "
                    + "%d routed, %d met their minimum, %d fell back to the plain search; setup pushed under the floor on %d endpoints, "
                    + "%d sinks no slower than before, %d left unrouted; PIPs on the touched nets %d -> %d; analysis %d ms, route %d ms",
                    whsBefore, whsAfter, violatingBefore, violatingAfter, belowMarginBefore, belowMarginAfter, wnsBefore, wnsAfter,
                    endpointsTargeted, pathsBudgeted, sinksBudgeted, skippedNoNetEdge, skippedCrossing, skippedSetupRoom, skippedNoRouteDelay, routed, budgetMet, fallbacks,
                    setupPushedUnderFloor, sinksNotSlower, unroutedSinks,
                    pipsBefore, pipsAfter, analysisMs, routeMs);
        }
    }

    /**
     * One round of delay-budgeted detours on a routed Versal design: the model's slack analysis
     * (four corners, clock model) finds every endpoint whose slow-process hold slack is below the
     * margin; the last net connection on its worst hold path gets a budget of the deficit
     * (converted to the router's slow-max units by the connection's own slow-max/slow-min ratio),
     * capped by the endpoint's setup slack above the floor; the sinks are unrouted and re-routed
     * by this router; the analysis is updated incrementally for the after numbers.
     * @param marginPs endpoints below this hold slack (ps) are targeted, and the budget aims at it
     * @param setupFloorPs an endpoint whose setup slack minus the deficit would fall below this is left alone
     * @param setupUncertaintyPs the setup uncertainty of the analysis
     * @param maxExtraPs the budget window's width above the minimum (ps, router units)
     */
    public static Outcome fixHoldByDetour(Design design, float marginPs, float setupFloorPs, float setupUncertaintyPs, float maxExtraPs) {
        return fixHoldByDetour(design, null, marginPs, setupFloorPs, setupUncertaintyPs, maxExtraPs);
    }

    /**
     * As {@link #fixHoldByDetour(Design, float, float, float, float)}, over an existing analysis of the design
     * (brought up to date incrementally; a new one is built when {@code sa} is null). The analysis used is
     * returned in {@link Outcome#analysis} so that further rounds skip the build.
     */
    public static Outcome fixHoldByDetour(Design design, VersalSlackAnalysis sa, float marginPs, float setupFloorPs, float setupUncertaintyPs, float maxExtraPs) {
        Outcome out = new Outcome();
        long t0 = System.currentTimeMillis();
        if (sa == null) {
            VersalTimingModel model0 = new VersalTimingModel(design.getDevice());
            VersalClockModel clockModel = new VersalClockModel();
            float period = VersalTimingReport.periodFromConstraints(design);
            if (period <= 0) throw new IllegalStateException("no create_clock -period in the design's constraints");
            sa = new VersalSlackAnalysis(design, model0, clockModel, period, setupUncertaintyPs, 0f);
            sa.run();
        } else {
            sa.update();
        }
        out.analysis = sa;
        VersalTimingModel model = sa.getGraph().getModel();
        out.analysisMs = System.currentTimeMillis() - t0;
        out.whsBefore = sa.getWHS();
        out.wnsBefore = sa.getWNS();
        int iMax = model.indexOf(VersalCorner.SLOW_MAX), iMin = model.indexOf(VersalCorner.SLOW_MIN);

        // targets: the last net edge on each violating endpoint's worst hold path, at the worse of its two processes
        int iFastMin = model.indexOf(VersalCorner.FAST_MIN);
        Map<SitePinInst, float[]> want = new HashMap<>();   // sink -> {deficit, setup room} at the hold corner (ps)
        Map<SitePinInst, Integer> holdCornerOfSink = new HashMap<>();   // the min corner of the sink's largest deficit
        Map<SitePinInst, List<VersalSlackAnalysis.Result>> endpointsOfSink = new HashMap<>();
        Map<VersalTimingGraph.Vertex, Float> setupBefore = new HashMap<>();
        Map<VersalTimingGraph.Vertex, VersalSlackAnalysis.Result> worstOf = new LinkedHashMap<>();
        for (VersalSlackAnalysis.Result r : sa.getResults()) {
            setupBefore.merge(r.endpoint, r.setupSlack, Math::min);
            VersalSlackAnalysis.Result h = worstOf.get(r.endpoint);
            if (h == null || r.holdSlack < h.holdSlack) worstOf.put(r.endpoint, r);
        }
        List<VersalTimingGraph.Vertex> below = new ArrayList<>();
        for (VersalSlackAnalysis.Result r : worstOf.values()) {
            if (r.holdSlack < 0) out.violatingBefore++;
            if (r.holdSlack >= marginPs) continue;
            out.belowMarginBefore++;
            out.endpointsTargeted++;
            below.add(r.endpoint);
        }
        // every short path into each endpoint, not only its worst: once the worst is slowed the next one would
        // take over and cost another round (32x8: 20 endpoints of 2,634 after one round)
        Map<VersalTimingGraph.Vertex, List<VersalSlackAnalysis.PathSlack>> shortPaths = sa.shortHoldPaths(below, marginPs, SHORT_PATH_DEPTH, SHORT_PATHS_PER_ENDPOINT);
        for (VersalTimingGraph.Vertex v : below) {
            VersalSlackAnalysis.Result r = worstOf.get(v);
            List<VersalSlackAnalysis.PathSlack> paths = shortPaths.get(v);
            if (paths == null) {
                // fan-in deeper than the walk: the worst path alone
                VersalSlackAnalysis.PathSlack ps = new VersalSlackAnalysis.PathSlack();
                ps.path = sa.getGraph().getPath(r.endpoint, r.fast ? iFastMin : iMin, r.holdTag);
                ps.launch = ps.path.isEmpty() ? null : ps.path.get(0).src; ps.endpoint = v; ps.fast = r.fast; ps.holdSlack = r.holdSlack;
                paths = new ArrayList<>(); paths.add(ps);
            }
            boolean anyNet = false;
            for (VersalSlackAnalysis.PathSlack ps : paths) {
                SitePinInst sink = null;
                for (int i = ps.path.size() - 1; i >= 0; i--) {
                    VersalTimingGraph.Edge e = ps.path.get(i);
                    if ("net".equals(e.kind) && e.sinkPin != null) { sink = e.sinkPin; break; }
                }
                Net net = sink == null ? null : sink.getNet();
                if (net == null || net.getSource() == null || net.isStaticNet() || net.isClockNet()) continue;
                anyNet = true;
                // SLR crossings are left to a route-through detour on the source side (HoldFixer): the budgeted search
                // cannot reach an SLL input pin or a sink across the boundary inside its window, and the plain search
                // it falls back to re-routes the connection shorter than it was (32x8: a crossing entry went from
                // 1562 to 913 ps and its endpoint from -2 to -224 ps)
                if (sink.getName().startsWith("LAG") || (ps.launch != null && VersalClockArrivals.crossesSlr(ps.launch, v))) { out.skippedCrossing++; continue; }
                // aim past the margin: the hold-corner deficit converted to router units lands 5-10% short in the
                // full model on large deficits, and the search settles just above the minimum (32x8: 20 of 2,614
                // connections met their budget and ended 29-49 ps of hold, costing a second round for +30 ps)
                float deficit = marginPs + BUDGET_OVERSHOOT_PS - ps.holdSlack;
                float room = setupBefore.get(v) - setupFloorPs;
                if (room < deficit) deficit = marginPs - ps.holdSlack;   // no room for the overshoot: the bare deficit
                if (room < deficit) { out.skippedSetupRoom++; continue; }
                out.pathsBudgeted++;
                int corner = ps.fast ? iFastMin : iMin;
                float[] w = want.computeIfAbsent(sink, k -> new float[] {0f, Float.MAX_VALUE});
                if (deficit > w[0]) { w[0] = deficit; holdCornerOfSink.put(sink, corner); }
                w[1] = Math.min(w[1], room);
                List<VersalSlackAnalysis.Result> eps = endpointsOfSink.computeIfAbsent(sink, k -> new ArrayList<>());
                if (!eps.contains(r)) eps.add(r);
            }
            if (!anyNet) out.skippedNoNetEdge++;
        }

        RWRouteConfig config = new RWRouteConfig(new String[] {"--fixBoundingBox", "--useUTurnNodes", "--nonTimingDriven"});
        HoldFixRouter router = new HoldFixRouter(design, config, new ArrayList<>(want.keySet()), false, new ArrayList<>());
        Map<Net, List<SitePinInst>> byNet = new HashMap<>();
        Map<Net, Map<SitePinInst, VersalTimingModel.SinkDelay>> netDelays = new HashMap<>();
        for (Map.Entry<SitePinInst, float[]> e : want.entrySet()) {
            SitePinInst sink = e.getKey();
            Net net = sink.getNet();
            float lb = router.routeDelay(net, sink);
            if (Float.isNaN(lb)) { out.skippedNoRouteDelay++; continue; }
            VersalTimingModel.SinkDelay sd = netDelays.computeIfAbsent(net, model::calcNetDelays).get(sink);
            int iLo = holdCornerOfSink.getOrDefault(sink, iMin);
            float ratio = iLo == iMin ? 1.4f : 2.0f;
            if (sd != null && sd.routed && sd.interconnect[iLo] > 0f) {
                ratio = Math.max(1f, Math.min(4f, sd.interconnect[iMax] / sd.interconnect[iLo]));
            }
            // the hold deficit is at the hold corner and needs the ratio; the setup room is slow-max already
            // (the router's units), and the full model charges a detour about 1.1x the marginal sum
            float extra = e.getValue()[0] * ratio;
            float maxExtra = Math.min(e.getValue()[1] / SETUP_ROOM_SAFETY, extra + maxExtraPs);
            if (maxExtra < extra) { out.skippedSetupRoom++; continue; }
            router.setDelayBudget(sink, lb + extra, lb + maxExtra, lb);
            byNet.computeIfAbsent(net, n -> new ArrayList<>()).add(sink);
        }
        out.sinksBudgeted = router.getDelayBudgets().size();
        Map<Net, List<PIP>> pipsBefore = new HashMap<>();
        for (Net net : byNet.keySet()) { out.pipsBefore += net.getPIPs().size(); pipsBefore.put(net, new ArrayList<>(net.getPIPs())); }
        // A sink reached through a bounce node the net also routes on to another of its sinks (an SRL data pin or
        // a flop's [A-H]X, with a LUT pin of the same slice fed from the same bounce): unrouting the pin alone leaves
        // the node in the net's PIPs, the partial router then rebuilds the connection from them and never routes it
        // with its budget (the FSA's last hold endpoints). Its siblings are unrouted with it and routed again plainly
        // after the budgeted pass, off the detour.
        List<SitePinInst> siblings = siblingSinksOnBudgetedNodes(byNet);
        for (Map.Entry<Net, List<SitePinInst>> e : byNet.entrySet()) DesignTools.unroutePins(e.getKey(), e.getValue());
        if (!siblings.isEmpty()) System.out.printf("[HoldFixRouter] %d sibling sinks share a bounce node with a budgeted sink: unrouted with it, routed again after the budgeted pass%n", siblings.size());
        router.setDeferredSinks(siblings);

        t0 = System.currentTimeMillis();
        router.initialize();
        router.route();
        out.routeMs = System.currentTimeMillis() - t0;
        List<SitePinInst> unrouted = new ArrayList<>();
        int notTried = 0;
        for (Map.Entry<SitePinInst, DelayBudget> e : router.getDelayBudgets().entrySet()) {
            Float a = router.getAchievedDelay(e.getKey());
            if (a != null && !Float.isNaN(a)) { out.routed++; if (a >= e.getValue().min - 10f) out.budgetMet++; }
            else if (!e.getKey().isRouted()) unrouted.add(e.getKey());
            if (a == null && notTried++ < 30) {
                System.out.println("[HoldFixRouter] budgeted sink never routed by the budgeted search: " + e.getKey() + " of " + e.getKey().getNet().getName()
                        + ": " + router.describeSink(e.getKey()));
            }
        }
        for (SitePinInst p : siblings) { p.setRouted(false); unrouted.add(p); }
        // A connection the budgeted search and its fallback both abandon must not stay unrouted (the 16x16
        // FSA shipped one such pin in a routed checkpoint): route it again on any wire, like a reverted sink.
        // The deferred siblings are routed here too, with the budgeted routes of their nets fixed so that
        // the pass can only join a detour along it (an arc into a fixed PIP's node is locked), not re-drive
        // its bounce node from a shorter path.
        if (!unrouted.isEmpty()) {
            List<PIP> locked = new ArrayList<>();
            for (SitePinInst sib : new HashSet<>(siblings)) {
                Net net = sib.getNet();
                Map<Node, PIP> byEnd = new HashMap<>();
                for (PIP pip : net.getPIPs()) byEnd.put(pip.isReversed() ? pip.getStartNode() : pip.getEndNode(), pip);
                for (SitePinInst p : byNet.get(net)) {
                    if (!router.getDelayBudgets().containsKey(p)) continue;
                    for (PIP pip = byEnd.get(p.getConnectedNode()); pip != null && !pip.isPIPFixed(); pip = byEnd.get(pip.isReversed() ? pip.getEndNode() : pip.getStartNode())) {
                        pip.setIsPIPFixed(true);
                        locked.add(pip);
                    }
                }
            }
            HoldFixRouter plain = new HoldFixRouter(design, config, unrouted, false, new ArrayList<>());
            plain.initialize();
            plain.route();
            for (PIP pip : locked) pip.setIsPIPFixed(false);
            for (SitePinInst p : unrouted) if (!p.isRouted()) out.unroutedSinks++;
            System.out.printf("[HoldFixRouter] %d sinks left unrouted by the budgeted search routed again plainly (%d deferred siblings, %d PIPs of their nets' budgeted routes held fixed); %d still unrouted%n", unrouted.size(), siblings.size(), locked.size(), out.unroutedSinks);
        }

        long[] stats = router.getBudgetedSearchStats();
        out.fallbacks = (int) stats[3];

        t0 = System.currentTimeMillis();
        sa.update();
        // setup check over every endpoint (reported, not undone: restoring a branch after other detours were
        // routed around it produced node conflicts and invalid site programming on the 32x8, and reserving
        // the branch's nodes for the net makes the base graph exclude them from every new path)
        Set<Net> revert = new HashSet<>();
        for (VersalSlackAnalysis.Result r : sa.getResults()) {
            if (r.fast || r.setupSlack >= setupFloorPs) continue;
            Float before = setupBefore.get(r.endpoint);
            if (before == null || before < setupFloorPs) continue;
            out.setupPushedUnderFloor++;
        }
        for (Map.Entry<SitePinInst, DelayBudget> e : router.getDelayBudgets().entrySet()) {
            Float a = router.getAchievedDelay(e.getKey());
            if (a == null || Float.isNaN(a) || a < e.getValue().lowerBound + 10f) out.sinksNotSlower++;
        }
        out.analysisMs += System.currentTimeMillis() - t0;
        out.whsAfter = sa.getWHS();
        out.wnsAfter = sa.getWNS();
        for (Net net : byNet.keySet()) out.pipsAfter += net.getPIPs().size();
        Map<VersalTimingGraph.Vertex, Float> holdAfter = new HashMap<>();
        for (VersalSlackAnalysis.Result r : sa.getResults()) holdAfter.merge(r.endpoint, r.holdSlack, Math::min);
        for (float h : holdAfter.values()) {
            if (h < 0) out.violatingAfter++;
            if (h < marginPs) out.belowMarginAfter++;
        }
        // per-sink outcome for the log: budget vs achieved, and the endpoints' hold/setup slack before -> after
        StringBuilder sb = new StringBuilder();
        List<SitePinInst> sinks = new ArrayList<>(router.getDelayBudgets().keySet());
        sinks.sort((a, b) -> a.toString().compareTo(b.toString()));
        for (SitePinInst sink : sinks) {
            DelayBudget b = router.getDelayBudgets().get(sink);
            Float a = router.getAchievedDelay(sink);
            sb.append(String.format("%-45s budget [%.0f, %.0f] from %.0f, routed %s%s;", sink, b.min, b.max, b.lowerBound,
                    a == null ? "-" : Float.isNaN(a) ? "unrouted" : String.format("%.0f", a), revert.contains(sink.getNet()) ? " (reverted)" : ""));
            for (VersalSlackAnalysis.Result before : endpointsOfSink.getOrDefault(sink, Collections.emptyList())) {
                VersalSlackAnalysis.Result[] now = sa.getResults(before.endpoint);
                VersalSlackAnalysis.Result after = now == null ? null : now[before.fast ? 1 : 0];
                sb.append(String.format(" %s hold %.0f -> %s setup %.0f -> %s", before.endpoint.getName(), before.holdSlack,
                        after == null ? "?" : String.format("%.0f", after.holdSlack), before.setupSlack, after == null ? "?" : String.format("%.0f", after.setupSlack)));
            }
            sb.append('\n');
        }
        System.out.print(sb);
        System.out.printf("[HoldFixRouter] budgeted search: %d popped, %d pushed, %d stale skipped, %d fallbacks, %d searches out of pops took the best route seen%n", stats[0], stats[1], stats[2], stats[3], stats[4]);
        System.out.println("[HoldFixRouter] " + out);
        return out;
    }

    /**
     * The other sink pins of each net whose route passes through the INT node of one of the net's budgeted
     * sinks: they have to be unrouted with it for its connection to be open to the budgeted search.
     */
    private static List<SitePinInst> siblingSinksOnBudgetedNodes(Map<Net, List<SitePinInst>> byNet) {
        List<SitePinInst> siblings = new ArrayList<>();
        for (Map.Entry<Net, List<SitePinInst>> e : byNet.entrySet()) {
            Net net = e.getKey();
            if (net.getSinkPins().size() <= e.getValue().size()) continue;
            Map<Node, List<Node>> downhill = new HashMap<>();
            for (PIP pip : net.getPIPs()) {
                Node start = pip.isReversed() ? pip.getEndNode() : pip.getStartNode();
                Node end = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
                downhill.computeIfAbsent(start, k -> new ArrayList<>()).add(end);
            }
            Set<Node> reached = new HashSet<>();
            Deque<Node> stack = new ArrayDeque<>();
            for (SitePinInst p : e.getValue()) {
                Node n = RouterHelper.projectInputPinToINTNode(p);
                if (n != null && reached.add(n)) stack.push(n);
            }
            while (!stack.isEmpty()) {
                for (Node child : downhill.getOrDefault(stack.pop(), Collections.emptyList())) if (reached.add(child)) stack.push(child);
            }
            Set<SitePinInst> budgeted = new HashSet<>(e.getValue());
            for (SitePinInst q : net.getSinkPins()) {
                if (budgeted.contains(q)) continue;
                // (not q.isRouted(): the flag is stale on a checkpoint no router has refreshed; the walk is the proof)
                Node n = RouterHelper.projectInputPinToINTNode(q);
                if ((n != null && reached.contains(n)) || reached.contains(q.getConnectedNode())) {
                    siblings.add(q);
                    e.getValue().add(q);
                }
            }
        }
        return siblings;
    }

    /**
     * HoldFixRouter routed.dcp out.dcp [--margin ps] [--setup-floor ps] [--setup-uncertainty ps] [--max-extra ps]:
     * one round of {@link #fixHoldByDetour} on a routed Versal checkpoint.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: HoldFixRouter routed.dcp out.dcp [--margin ps] [--setup-floor ps] [--setup-uncertainty ps] [--max-extra ps]");
            return;
        }
        float margin = 50f, setupFloor = 150f, setupUnc = 73f, maxExtra = 600f;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--margin": margin = Float.parseFloat(args[++i]); break;
                case "--setup-floor": setupFloor = Float.parseFloat(args[++i]); break;
                case "--setup-uncertainty": setupUnc = Float.parseFloat(args[++i]); break;
                case "--max-extra": maxExtra = Float.parseFloat(args[++i]); break;
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        Design design = Design.readCheckpoint(args[0]);
        Outcome out = fixHoldByDetour(design, margin, setupFloor, setupUnc, maxExtra);
        design.writeCheckpoint(args[1]);
        System.out.println("[HoldFixRouter] wrote " + args[1] + ": " + out);
    }
}
