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
import com.xilinx.rapidwright.timing.versal.VersalSlackAnalysis;
import com.xilinx.rapidwright.timing.versal.VersalTimingGraph;
import com.xilinx.rapidwright.timing.versal.VersalTimingModel;
import com.xilinx.rapidwright.timing.versal.VersalTimingReport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
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
    }

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
        if (budget == null || connection.isCrossSLR()) {
            super.routeIndirectConnection(connection);
            return;
        }
        float weight = delayCostWeight;
        for (int attempt = 0; ; attempt++) {
            routeBudgetedConnection(connection, budget, weight);
            float achieved = connection.isRouted() ? routeDelayOf(connection) : Float.NaN;
            achievedDelay.put(connection, achieved);
            if (!connection.isRouted() || achieved >= budget.min - retryToleranceUnder || attempt >= maxRetries) {
                if (connection.isRouted() && achieved < budget.min - retryToleranceUnder) {
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

        prepareRouteConnection(state);   // rips up, marks the targets, pushes the source (through push())

        Entry sinkEntry = null;
        Entry e;
        while ((e = holdQueue.poll()) != null) {
            if (bestEntry.get(e.node) != e) { holdStaleSkipped++; continue; }   // a cheaper path reached this node since
            state.nodesPopped++;
            if (e.node.isTarget()) { sinkEntry = e; break; }
            currentEntry = e;
            // the base class's early termination (set when a child is the uncongested target) is not
            // wanted here: the target must be popped in cost order like any node
            state.earlyTermination = false;
            state.queue.clear();
            exploreAndExpand(state, e.node);
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
                if (x.node == childRnode) { restorePrev(childRnode); return; }
            }
            upstreamDelay = parent.upstreamDelay + childRnode.getDelay()
                    + DelayEstimatorBase.getExtraDelay(childRnode, DelayEstimatorBase.isLong(parent.node));
        }
        // expected total delay of a route through this node: what is behind plus the estimate to the sink
        RouteNode sinkRnode = connection.getSinkRnode();
        int deltaX = Math.abs(childRnode.getEndTileXCoordinate() - sinkRnode.getBeginTileXCoordinate());
        int deltaY = Math.abs(childRnode.getEndTileYCoordinate() - sinkRnode.getBeginTileYCoordinate());
        float expected = upstreamDelay + 100f * (deltaX * routingGraph.getEstimatedDelayPerTileX() + deltaY * routingGraph.getEstimatedDelayPerTileY());
        float total = newTotalPathCost + currentWeight * valleyCost(currentBudget, expected);

        Entry best = bestEntry.get(childRnode);
        if (best != null && total >= best.total) { restorePrev(childRnode); return; }
        childRnode.setLowerBoundTotalPathCost(total);
        childRnode.setUpstreamPathCost(newPartialPathCost);
        Entry entry = new Entry(childRnode, total, upstreamDelay, parent);
        bestEntry.put(childRnode, entry);
        holdQueue.add(entry);
    }

    /** evaluateCostAndPush sets the child's prev before costing; a rejected push puts the best path's parent back. */
    private void restorePrev(RouteNode child) {
        Entry best = bestEntry.get(child);
        if (best != null && best.prev != null) child.setPrev(best.prev.node);
    }

    /**
     * The delay part of the cost (RCV eq. 14, in units of 100 ps): linear towards the target from
     * both sides, quadratic outside the window.
     */
    float valleyCost(DelayBudget b, float expected) {
        float target = b.target();
        float shortCrit = target > 0 ? (float) Math.pow(Math.max(0f, target - b.lowerBound) / target, SHORT_PATH_EXPONENT) : 0f;
        float longCrit = longPathCriticality;
        float cost = longCrit * expected;
        cost += (shortCrit + longCrit) * Math.max(0f, target - expected);
        float over = Math.max(0f, expected - b.max);
        float under = Math.max(0f, b.min - expected);
        cost += over * over / QUADRATIC_NORM_PS + under * under / QUADRATIC_NORM_PS;
        return cost / 100f;
    }

    /** Statistics of the budgeted searches of the last {@link #route()}: nodes popped, nodes pushed, stale entries skipped. */
    public long[] getBudgetedSearchStats() {
        return new long[] {holdNodesPopped, holdNodesPushed, holdStaleSkipped};
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
        public int endpointsTargeted, sinksBudgeted, skippedNoNetEdge, skippedSetupRoom, skippedNoRouteDelay, routed, budgetMet;
        public long pipsBefore, pipsAfter;
        public long analysisMs, routeMs;
        @Override
        public String toString() {
            return String.format("hold: WHS %.0f -> %.0f ps, endpoints below 0: %d -> %d, below margin: %d -> %d; setup: WNS %.0f -> %.0f ps; "
                    + "%d endpoints targeted -> %d sinks budgeted (%d without a net edge, %d without setup room, %d without a route delay); "
                    + "%d routed, %d met their minimum; PIPs on the touched nets %d -> %d; analysis %d ms, route %d ms",
                    whsBefore, whsAfter, violatingBefore, violatingAfter, belowMarginBefore, belowMarginAfter, wnsBefore, wnsAfter,
                    endpointsTargeted, sinksBudgeted, skippedNoNetEdge, skippedSetupRoom, skippedNoRouteDelay, routed, budgetMet,
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
        Outcome out = new Outcome();
        long t0 = System.currentTimeMillis();
        VersalTimingModel model = new VersalTimingModel(design.getDevice());
        VersalClockModel clockModel = new VersalClockModel();
        float period = VersalTimingReport.periodFromConstraints(design);
        if (period <= 0) throw new IllegalStateException("no create_clock -period in the design's constraints");
        VersalSlackAnalysis sa = new VersalSlackAnalysis(design, model, clockModel, period, setupUncertaintyPs, 0f);
        sa.run();
        out.analysisMs = System.currentTimeMillis() - t0;
        out.whsBefore = sa.getWHS();
        out.wnsBefore = sa.getWNS();
        int iMax = model.indexOf(VersalCorner.SLOW_MAX), iMin = model.indexOf(VersalCorner.SLOW_MIN);

        // targets: the last net edge on each violating endpoint's worst hold path
        Map<SitePinInst, float[]> want = new HashMap<>();   // sink -> {deficit, setup room} at the hold corner (ps)
        Map<SitePinInst, List<VersalSlackAnalysis.Result>> endpointsOfSink = new HashMap<>();
        for (VersalSlackAnalysis.Result r : sa.getResults()) {
            if (r.fast) continue;
            if (r.holdSlack < 0) out.violatingBefore++;
            if (r.holdSlack >= marginPs) continue;
            out.belowMarginBefore++;
            out.endpointsTargeted++;
            List<VersalTimingGraph.Edge> path = sa.getGraph().getPath(r.endpoint, iMin, r.holdTag);
            SitePinInst sink = null;
            for (int i = path.size() - 1; i >= 0; i--) {
                VersalTimingGraph.Edge e = path.get(i);
                if ("net".equals(e.kind) && e.sinkPin != null) { sink = e.sinkPin; break; }
            }
            Net net = sink == null ? null : sink.getNet();
            if (net == null || net.getSource() == null || net.isStaticNet() || net.isClockNet()) { out.skippedNoNetEdge++; continue; }
            float deficit = marginPs - r.holdSlack;
            float room = r.setupSlack - setupFloorPs;
            if (room < deficit) { out.skippedSetupRoom++; continue; }
            float[] w = want.computeIfAbsent(sink, k -> new float[] {0f, Float.MAX_VALUE});
            w[0] = Math.max(w[0], deficit);
            w[1] = Math.min(w[1], room);
            endpointsOfSink.computeIfAbsent(sink, k -> new ArrayList<>()).add(r);
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
            float ratio = 1.4f;
            if (sd != null && sd.routed && sd.interconnect[iMin] > 0f) {
                ratio = Math.max(1f, Math.min(3f, sd.interconnect[iMax] / sd.interconnect[iMin]));
            }
            float extra = e.getValue()[0] * ratio;
            float maxExtra = Math.min(e.getValue()[1] * ratio, extra + maxExtraPs);
            router.setDelayBudget(sink, lb + extra, lb + maxExtra, lb);
            byNet.computeIfAbsent(net, n -> new ArrayList<>()).add(sink);
        }
        out.sinksBudgeted = router.getDelayBudgets().size();
        for (Net net : byNet.keySet()) out.pipsBefore += net.getPIPs().size();
        for (Map.Entry<Net, List<SitePinInst>> e : byNet.entrySet()) DesignTools.unroutePins(e.getKey(), e.getValue());

        t0 = System.currentTimeMillis();
        router.initialize();
        router.route();
        out.routeMs = System.currentTimeMillis() - t0;
        for (Net net : byNet.keySet()) out.pipsAfter += net.getPIPs().size();
        for (Map.Entry<SitePinInst, DelayBudget> e : router.getDelayBudgets().entrySet()) {
            Float a = router.getAchievedDelay(e.getKey());
            if (a != null && !Float.isNaN(a)) { out.routed++; if (a >= e.getValue().min - 10f) out.budgetMet++; }
        }

        t0 = System.currentTimeMillis();
        sa.update();
        out.analysisMs += System.currentTimeMillis() - t0;
        out.whsAfter = sa.getWHS();
        out.wnsAfter = sa.getWNS();
        for (VersalSlackAnalysis.Result r : sa.getResults()) {
            if (r.fast) continue;
            if (r.holdSlack < 0) out.violatingAfter++;
            if (r.holdSlack < marginPs) out.belowMarginAfter++;
        }
        // per-sink outcome for the log: budget vs achieved, and the endpoints' hold/setup slack before -> after
        StringBuilder sb = new StringBuilder();
        List<SitePinInst> sinks = new ArrayList<>(router.getDelayBudgets().keySet());
        sinks.sort((a, b) -> a.toString().compareTo(b.toString()));
        for (SitePinInst sink : sinks) {
            DelayBudget b = router.getDelayBudgets().get(sink);
            Float a = router.getAchievedDelay(sink);
            sb.append(String.format("%-45s budget [%.0f, %.0f] from %.0f, routed %s;", sink, b.min, b.max, b.lowerBound,
                    a == null ? "-" : Float.isNaN(a) ? "unrouted" : String.format("%.0f", a)));
            for (VersalSlackAnalysis.Result before : endpointsOfSink.getOrDefault(sink, Collections.emptyList())) {
                VersalSlackAnalysis.Result[] now = sa.getResults(before.endpoint);
                VersalSlackAnalysis.Result after = now == null ? null : now[0];
                sb.append(String.format(" %s hold %.0f -> %s setup %.0f -> %s", before.endpoint.getName(), before.holdSlack,
                        after == null ? "?" : String.format("%.0f", after.holdSlack), before.setupSlack, after == null ? "?" : String.format("%.0f", after.setupSlack)));
            }
            sb.append('\n');
        }
        System.out.print(sb);
        long[] stats = router.getBudgetedSearchStats();
        System.out.printf("[HoldFixRouter] budgeted search: %d popped, %d pushed, %d stale skipped%n", stats[0], stats[1], stats[2]);
        System.out.println("[HoldFixRouter] " + out);
        return out;
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
