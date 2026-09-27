/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.router;

import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.rwroute.NodeStatus;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.rwroute.RouterHelper.NodeWithPrev;
import com.xilinx.rapidwright.router.VTreeType;
import com.xilinx.rapidwright.timing.versal.VersalClockModel;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of utility methods for routing clocks on
 * the Versal architecture.
 *
 * Created on: Nov 1, 2024
 */
public class VersalClockRouting {

    public static final String USER_CLOCK_VTREE_TYPE = "USER_CLOCK_VTREE_TYPE";

    private static final EnumSet<IntentCode> hRouteTypes;
    private static final EnumSet<IntentCode> vRouteTypes;
    private static final EnumSet<IntentCode> allRouteTypes;
    
    private static Map<String, Map<Integer, VersalClockTree>> vdistrTrees;
    
    static {
        hRouteTypes = EnumSet.of(
                IntentCode.NODE_GLOBAL_HROUTE,
                IntentCode.NODE_GLOBAL_HROUTE_HSR, 
                IntentCode.NODE_GLOBAL_HROUTE_MED, 
                IntentCode.NODE_GLOBAL_HROUTE_SLOW); 
        vRouteTypes = EnumSet.of(IntentCode.NODE_GLOBAL_VROUTE);
        allRouteTypes = EnumSet.of(
                IntentCode.NODE_GLOBAL_VROUTE, 
                IntentCode.NODE_GLOBAL_HROUTE,
                IntentCode.NODE_GLOBAL_HROUTE_HSR, 
                IntentCode.NODE_GLOBAL_HROUTE_MED,
                IntentCode.NODE_GLOBAL_HROUTE_SLOW);
    }
    
    public static class NodeWithPrevAndCost extends NodeWithPrev implements Comparable<NodeWithPrevAndCost> {
        private static final long serialVersionUID = 2103669135320260630L;
        protected int cost;
        protected int depth;
        public NodeWithPrevAndCost(Node node) {
            super(node);
            setCost(0);
            setDepth(1);
        }
        public NodeWithPrevAndCost(Node node, NodeWithPrev prev, int cost) {
            super(node, prev);
            setCost(cost);
            int depth = 1;
            NodeWithPrev curr = prev;
            while (curr.getPrev() != null) {
                depth++;
                curr = curr.getPrev();
            }
            setDepth(depth);
        }

        public NodeWithPrevAndCost(Node node, NodeWithPrevAndCost prev, int cost) {
            super(node, prev);
            setCost(cost);
            setDepth((prev == null ? 0 : prev.depth) + 1);
        }

        public void setCost(int cost) {
            this.cost = cost;
        }

        public void setDepth(int depth) {
            this.depth = depth;
        }

        @Override
        public int compareTo(NodeWithPrevAndCost that) {
            return Integer.compare(this.cost, that.cost);
        }
    }

    public static Node routeBUFGToNearestRoutingTrack(Net clk, Function<Node, NodeStatus> getNodeStatus) {
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        q.add(new NodeWithPrev(clk.getSource().getConnectedNode()));
        int watchDog = 300;
        while (!q.isEmpty()) {
            NodeWithPrev curr = q.poll();
            if (getNodeStatus.apply(curr) != NodeStatus.AVAILABLE) {
                continue;
            }
            
            IntentCode c = curr.getIntentCode();
            if (c == IntentCode.NODE_GLOBAL_HROUTE_HSR || c == IntentCode.NODE_GLOBAL_HROUTE) {
                List<Node> path = curr.getPrevPath();
                clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path));
                return curr;
            }
            for (Node downhill: curr.getAllDownhillNodes()) {
                q.add(new NodeWithPrev(downhill, curr));
            }
            if (watchDog-- == 0) {
                break;
            }
        }
        return null;
    }

    private static final String VROUTE = "VROUTE";
    
    public static Integer getRoutingTrackIndex(Node node) {
        if (node.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE) {
            String wireName = node.getWireName();
            return Integer.parseInt(wireName.substring(wireName.indexOf(VROUTE) + VROUTE.length()));
        }
        return null;
    }
    
    private static boolean isCentroidCandidateAvailable(Node node, Set<Integer> unavailableTracks) {
        Integer idx = getRoutingTrackIndex(node);
        if (idx == null) return false;
        return !unavailableTracks.contains(idx);
    }
    
    /**
     * Routes a clock from a routing track to a transition point where the clock.
     * fans out and transitions from clock routing tracks to clock distribution.
     * 
     * @param clk                The current clock net to contribute routing.
     * @param startingNode       The intermediate start point of the clock route.
     * @param targetCR        The center clock region or the clock region that is
     *                           one row above or below the center.
     * @param findCentroidHroute The flag to indicate the returned Node should be
     *                           HROUTE in the center or VROUTE going up or down.
     * @param getNodeStatus      Function to call to check if a node is available
     * @param usedRoutingTracks  A map to keep track of which routing tracks are
     *                           used for each region
     */
    public static Node routeToCentroid(Net clk, Node startingNode, ClockRegion targetCR,
            boolean findCentroidHroute, Function<Node, NodeStatus> getNodeStatus,
            Set<Integer> unavailableTracks) {
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        q.add(new NodeWithPrevAndCost(startingNode));
        int watchDog = 10000;
        Set<Node> visited = new HashSet<>();
        Tile crApproxCenterTile = targetCR.getApproximateCenter();
        EnumSet<IntentCode> targetCodes = findCentroidHroute ? allRouteTypes : vRouteTypes;
        
        // In Vivado solutions, we can always find the pattern:
        // ... -> NODE_GLOBAL_GCLK -> NODE_GLOBAL_VROUTE -> NODE_GLOBAL_VDISTR_LVL2 -> ...
        // and this is how we locate the VROUTE node

        while (!q.isEmpty()) {
            NodeWithPrevAndCost curr = q.poll();
            NodeWithPrev parent = curr.getPrev();
            if (parent != null) {
                IntentCode parentIntentCode = parent.getIntentCode();
                IntentCode currIntentCode = curr.getIntentCode();
                if (vRouteTypes.contains(parentIntentCode) && hRouteTypes.contains(currIntentCode)) {
                    // Disallow ability to go from VROUTE back to HROUTE
                    continue;
                }
            }
            if (getNodeStatus.apply(curr) != NodeStatus.AVAILABLE) {
                continue;
            }

            for (Node downhill : curr.getAllDownhillNodes()) {
                IntentCode downhillIntentCode = downhill.getIntentCode();
                // Only using routing lines to get to centroid
                if (!downhillIntentCode.isVersalClocking()) {
                    continue;
                }

                if (targetCR.equals(downhill.getTile().getClockRegion())
                        && downhillIntentCode == IntentCode.NODE_GLOBAL_VDISTR) {
                    NodeWithPrev centroid = curr;
                    while (!targetCodes.contains(centroid.getIntentCode())) {
                        centroid = centroid.getPrev();
                    }
                    if (isCentroidCandidateAvailable(centroid, unavailableTracks)) {
                        List<Node> path = centroid.getPrevPath();
                        clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path));
                        return centroid;
                    }
                }

                if (!findCentroidHroute && hRouteTypes.contains(downhillIntentCode)) {
                    continue;
                }
                if (!visited.add(downhill)) {
                    continue;
                }

                int cost = downhill.getTile().getManhattanDistance(crApproxCenterTile);
                q.add(new NodeWithPrevAndCost(downhill, curr, cost));
            }
            if (watchDog-- == 0) {
                return null;
            }
        }

        return null;
    }

    public static Map<ClockRegion, Node> routeVrouteToVerticalDistributionLines(Net clk,
                                                                                Node vroute,
                                                                                Collection<ClockRegion> clockRegions,
                                                                                Function<Node, NodeStatus> getNodeStatus) {
        // The VROUTE node is the precursor to the clock root, technically the first
        // VDISTR node is the center point. If we have more than one VROUTE->VDISTR
        // transition we end up with multiple clock roots
        NodeWithPrevAndCost clockRootNode = new NodeWithPrevAndCost(vroute);

        // Identify top and bottom clock region spine targets
        int minY = Integer.MAX_VALUE;
        int maxY = 0;
        int x = vroute.getTile().getClockRegion().getInstanceX();
        Device device = clk.getDesign().getDevice();
        for (ClockRegion cr : clockRegions) {
            minY = Math.min(minY, cr.getInstanceY());
            maxY = Math.max(maxY, cr.getInstanceY());
        }
        List<ClockRegion> verticalSpineCRs = new ArrayList<>();
        for (int i = minY; i <= maxY; i++) {
            verticalSpineCRs.add(device.getClockRegion(i, x));
        }

        VersalClockTree clkTree = getVersalClockTree(clk, device, minY, maxY);
        assert (clkTree != null);
        if (clkTree == null) {
            System.err.println("ERROR: No clock tree found for " + device + " Y" + minY + "-Y" + maxY
                    + " while routing clock " + clk + ", skew will be suboptimal.");
        }

        SpineRoute spine = routeSpine(clockRootNode, verticalSpineCRs, clkTree, getNodeStatus,
                Collections.emptyMap());
        spine = balanceLvl1Ends(clk, spine, clockRootNode, verticalSpineCRs, clockRegions, clkTree,
                getNodeStatus);
        Map<ClockRegion, Node> crToVdist = spine.crToVdist;
        clk.getPIPs().addAll(spine.pips);

        // Propagate all vdist nodes as sources for other non-spine CRs
        for (ClockRegion cr : clockRegions) {
            if (!crToVdist.containsKey(cr)) {
                ClockRegion correspondingCR = device.getClockRegion(cr.getRow(), x);
                Node vNode = crToVdist.get(correspondingCR);
                crToVdist.put(cr, vNode);
            }
        }
        // Remove any spine CRs that don't have sinks
        for (ClockRegion cr : verticalSpineCRs) {
            if (!clockRegions.contains(cr)) {
                crToVdist.remove(cr);
            }
        }

        return crToVdist;
    }

    /** The vertical distribution from the root to each spine clock region's VDISTR node. */
    private static class SpineRoute {
        final Set<PIP> pips = new HashSet<>();
        final Map<ClockRegion, Node> crToVdist = new HashMap<>();
    }

    private static final Set<IntentCode> VDISTR_ROUTE_CODES = EnumSet.of(
            IntentCode.NODE_GLOBAL_VDISTR,
            IntentCode.NODE_GLOBAL_VDISTR_LVL1,
            IntentCode.NODE_GLOBAL_VDISTR_LVL2,
            IntentCode.NODE_GLOBAL_VDISTR_LVL21,
            IntentCode.NODE_GLOBAL_VDISTR_LVL3,
            IntentCode.NODE_GLOBAL_VDISTR_SHARED,
            IntentCode.NODE_GLOBAL_GCLK);

    /**
     * Routes the root to every spine clock region along the tree's levels. {@code lvl1LowerEnd}
     * fixes the end each listed NODE_GLOBAL_VDISTR_LVL1 wire is driven from (true: the lower
     * clock region's driver); the search picks the end of any other.
     */
    private static SpineRoute routeSpine(NodeWithPrevAndCost clockRootNode, List<ClockRegion> verticalSpineCRs,
            VersalClockTree clkTree, Function<Node, NodeStatus> getNodeStatus, Map<Node, Boolean> lvl1LowerEnd) {
        SpineRoute route = new SpineRoute();
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        Set<Node> visited = new HashSet<>();
        for (ClockRegion cr : verticalSpineCRs) {
            q.clear();
            visited.clear();
            q.add(clockRootNode);

            List<Pair<IntentCode, ClockRegion>> distrPath = getVDistrPath(clkTree, cr);
            nextDistrLevel: for (Pair<IntentCode, ClockRegion> target : distrPath) {
                IntentCode targetIC = target.getFirst();
                ClockRegion targetCR = target.getSecond();
                Tile crApproxCenterTile = targetCR.getApproximateCenter();

                while (!q.isEmpty()) {
                    NodeWithPrevAndCost curr = q.poll();
                    if (getNodeStatus.apply(curr) != NodeStatus.AVAILABLE) {
                        continue;
                    }
                    IntentCode currIC = curr.getIntentCode();
                    ClockRegion currCR = curr.getTile().getClockRegion();
                    if (currCR != null && targetCR == currCR && currIC == targetIC) {
                        q.clear();
                        visited.clear();
                        q.add(curr);
                        route.pips.addAll(RouterHelper.getPIPsFromNodes(curr.getPrevPath()));
                        if (targetIC == IntentCode.NODE_GLOBAL_VDISTR) {
                            route.crToVdist.put(cr, curr);
                        }
                        continue nextDistrLevel;
                    }

                    for (Node downhill : curr.getAllDownhillNodes()) {
                        if (!VDISTR_ROUTE_CODES.contains(downhill.getIntentCode())) {
                            continue;
                        }
                        Boolean lower = lvl1LowerEnd.get(downhill);
                        if (lower != null && isLowerEndDriver(curr, downhill) != lower) {
                            continue;
                        }
                        if (!visited.add(downhill)) {
                            continue;
                        }
                        int cost = downhill.getTile().getManhattanDistance(crApproxCenterTile) + (curr.depth * 100);
                        q.add(new NodeWithPrevAndCost(downhill, curr, cost));
                    }
                }
                throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
            }
        }
        return route;
    }

    /** A global clock buffer or distribution node above the leaves. */
    private static boolean isDistributionNode(Node n) {
        if (n == null) {
            return false;
        }
        IntentCode ic = n.getIntentCode();
        return ic.name().startsWith("NODE_GLOBAL_") && ic != IntentCode.NODE_GLOBAL_LEAF;
    }

    private static boolean isLowerEndDriver(Node driver, Node wire) {
        return driver.getTile().getClockRegion().getInstanceY() < wire.getTile().getClockRegion().getInstanceY();
    }

    /** At most this many LVL1 wires are balanced together (2^n spine routes). */
    private static final int MAX_BALANCED_LVL1 = 4;
    /** A combination of LVL1 ends replaces the search's choice only if it narrows the rows' spread by more. */
    private static final float BALANCE_MIN_GAIN_PS = 5;

    private static VersalClockModel spineDelayModel;
    private static boolean spineDelayModelFailed;

    private static synchronized VersalClockModel getSpineDelayModel() {
        if (spineDelayModel == null && !spineDelayModelFailed) {
            try {
                spineDelayModel = new VersalClockModel();
            } catch (RuntimeException e) {
                spineDelayModelFailed = true;
                System.err.println("WARNING: No Versal clock delay model (" + e.getMessage()
                        + "), clock spines are routed without balancing their LVL1 wires");
            }
        }
        return spineDelayModel;
    }

    /**
     * Chooses the end each NODE_GLOBAL_VDISTR_LVL1 wire of the spine is driven from. Such a wire
     * spans two clock regions with a driver at each end, and the stored trees name its level and
     * region but not the end; the end sets whether the rows below it take the wire's whole length
     * or none of it. Vivado picks the ends that balance the rows: on a mesh spanning SLR1 and SLR2
     * under interSLR it drives the SLR1 wire from its far (lower) end, making SLR1 as late as the
     * crossing makes SLR2, while the nearer end the search would pick left SLR2 0.6-1.0 ns late at
     * the crossing. Every combination of ends is routed and the one whose rows' VDISTR arrivals
     * (slow max corner, clock delay model) spread least is kept: across all rows, or within each
     * SLR for the intraSLR tree.
     */
    private static SpineRoute balanceLvl1Ends(Net clk, SpineRoute spine, NodeWithPrevAndCost clockRootNode,
            List<ClockRegion> verticalSpineCRs, Collection<ClockRegion> clockRegions, VersalClockTree clkTree,
            Function<Node, NodeStatus> getNodeStatus) {
        List<Node> choices = new ArrayList<>();
        for (PIP p : spine.pips) {
            Node wire = p.isBidirectional() && p.isReversed() ? p.getStartNode() : p.getEndNode();
            if (wire == null || wire.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR_LVL1 || choices.contains(wire)) {
                continue;
            }
            boolean upper = false, lower = false;
            for (Node up : wire.getAllUphillNodes()) {
                if (up.getIntentCode() != IntentCode.NODE_GLOBAL_GCLK || getNodeStatus.apply(up) != NodeStatus.AVAILABLE) {
                    continue;
                }
                if (isLowerEndDriver(up, wire)) {
                    lower = true;
                } else {
                    upper = true;
                }
            }
            if (upper && lower) {
                choices.add(wire);
            }
        }
        if (choices.isEmpty() || choices.size() > MAX_BALANCED_LVL1) {
            return spine;
        }
        VersalClockModel model = getSpineDelayModel();
        if (model == null) {
            return spine;
        }
        boolean perSLR = clkTree != null && clkTree.getVTreeType() == VTreeType.INTRA_SLR;
        List<PIP> trunk = pathFromSource(clk, clockRootNode);
        Float bestSpread = rowSpread(model, clk, trunk, spine, clockRegions, perSLR);
        if (bestSpread == null) {
            return spine;
        }
        SpineRoute best = spine;
        float initialSpread = bestSpread;
        int priced = 0;
        for (int mask = 0; mask < (1 << choices.size()); mask++) {
            Map<Node, Boolean> lowerEnd = new HashMap<>();
            for (int i = 0; i < choices.size(); i++) {
                lowerEnd.put(choices.get(i), (mask & (1 << i)) != 0);
            }
            SpineRoute candidate;
            try {
                candidate = routeSpine(clockRootNode, verticalSpineCRs, clkTree, getNodeStatus, lowerEnd);
            } catch (RuntimeException e) {
                continue;
            }
            Float spread = rowSpread(model, clk, trunk, candidate, clockRegions, perSLR);
            if (spread == null) {
                continue;
            }
            priced++;
            if (spread < bestSpread - BALANCE_MIN_GAIN_PS) {
                best = candidate;
                bestSpread = spread;
            }
        }
        System.out.printf("INFO: Clock %s spine: %d two-ended LVL1 wires, %d of %d end combinations routed as trees;"
                + " row arrival spread %.0f ps -> %.0f ps%n", clk, choices.size(), priced, 1 << choices.size(),
                initialSpread, bestSpread);
        return best;
    }

    /** Whether no node is driven by two different PIPs of the list. */
    private static boolean isTree(List<PIP> pips) {
        Map<Node, PIP> driver = new HashMap<>();
        for (PIP p : pips) {
            Node end = p.isBidirectional() && p.isReversed() ? p.getStartNode() : p.getEndNode();
            PIP prev = driver.put(end, p);
            if (prev != null && !prev.equals(p)) {
                return false;
            }
        }
        return true;
    }

    /** The clock net's PIPs from its source to the given node (the root's VROUTE). */
    private static List<PIP> pathFromSource(Net clk, Node root) {
        Map<Node, PIP> driving = new HashMap<>();
        for (PIP p : clk.getPIPs()) {
            Node end = p.isBidirectional() && p.isReversed() ? p.getStartNode() : p.getEndNode();
            if (end != null && isDistributionNode(end)) {
                driving.put(end, p);
            }
        }
        List<PIP> path = new ArrayList<>();
        Set<Node> seen = new HashSet<>();
        for (Node n = root; seen.add(n); ) {
            PIP p = driving.get(n);
            if (p == null) {
                break;
            }
            path.add(p);
            n = p.isBidirectional() && p.isReversed() ? p.getEndNode() : p.getStartNode();
        }
        return path;
    }

    /**
     * The spread (ps, slow max corner) of the modelled arrival at the VDISTR node of each spine row
     * that has loads: the clock's path from its source to the root plus the spine, from a zero root.
     */
    private static Float rowSpread(VersalClockModel model, Net clk, List<PIP> trunk, SpineRoute spine,
            Collection<ClockRegion> clockRegions, boolean perSLR) {
        List<PIP> pips = new ArrayList<>(trunk);
        pips.addAll(spine.pips);
        if (!isTree(pips)) {
            // Two paths drive one node (the forced ends can make them meet): not a route to price
            return null;
        }
        Net probe = new Net(clk.getName() + "_spine");
        probe.setPIPs(pips);
        VersalClockModel.ClockTree tree = model.analyze(probe);
        Set<Integer> loadedRows = new HashSet<>();
        for (ClockRegion cr : clockRegions) {
            loadedRows.add(cr.getInstanceY());
        }
        Map<Integer, float[]> minMaxBySLR = new HashMap<>();
        for (Map.Entry<ClockRegion, Node> e : spine.crToVdist.entrySet()) {
            ClockRegion cr = e.getKey();
            if (!loadedRows.contains(cr.getInstanceY())) {
                continue;
            }
            float[] state = tree.arriving.get(e.getValue());
            if (state == null) {
                return null;
            }
            float arrival = VersalClockModel.ClockTree.arrival(state)[0];
            int group = perSLR ? cr.getSLR().getId() : 0;
            float[] mm = minMaxBySLR.computeIfAbsent(group, k -> new float[] {Float.MAX_VALUE, -Float.MAX_VALUE});
            mm[0] = Math.min(mm[0], arrival);
            mm[1] = Math.max(mm[1], arrival);
        }
        float spread = 0;
        for (float[] mm : minMaxBySLR.values()) {
            spread = Math.max(spread, mm[1] - mm[0]);
        }
        return spread;
    }

    private static List<Pair<IntentCode, ClockRegion>> getVDistrPath(VersalClockTree clkTree,
            ClockRegion target) {
        return clkTree == null ? Arrays.asList(new Pair<>(IntentCode.NODE_GLOBAL_VDISTR, target))
                : clkTree.getClockRegionVDistrPath(target);
    }

    /**
     * For each target clock region, route from the provided vertical distribution line to a
     * horizontal distribution line that has a GLOBAL_GLK child node in this clock region.
     * This simulates the behavior of Vivado.
     * @param clk The current clock net
     * @param crMap A map of target clock regions and their respective vertical distribution lines
     * @return The map of target clock regions and their respective horizontal distribution lines.
     */
    public static Map<ClockRegion, Node> routeVerticalToHorizontalDistributionLines(Net clk,
                                                                                    Map<ClockRegion, Node> crMap,
                                                                                    Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Node> distLines = new HashMap<>();
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        nextClockRegion: for (Entry<ClockRegion,Node> e : crMap.entrySet()) {
            q.clear();
            Node vertDistLine = e.getValue();
            q.add(new NodeWithPrev(vertDistLine));
            ClockRegion targetCR = e.getKey();
            visited.clear();
            visited.add(vertDistLine);
            
            while (!q.isEmpty()) {
                NodeWithPrev curr = q.poll();
                if (getNodeStatus.apply(curr) != NodeStatus.AVAILABLE) {
                    continue;
                }
                NodeWithPrev parent = curr.getPrev();
                if (targetCR.equals(curr.getTile().getClockRegion()) &&
                    curr.getIntentCode() == IntentCode.NODE_GLOBAL_GCLK &&
                    parent.getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR_LOCAL) {
                    List<Node> path = curr.getPrevPath();
                    for (int i = 1; i < path.size(); i++) {
                        Node node = path.get(i);
                        if (i > 1) {
                            allPIPs.add(PIP.getArbitraryPIP(node, path.get(i-1)));
                        }
                    }
                    distLines.put(targetCR, parent);
                    continue nextClockRegion;
                }

                for (Node downhill: curr.getAllDownhillNodes()) {
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) {
                        continue;
                    }
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    q.add(new NodeWithPrev(downhill, curr));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + targetCR);
        }
        clk.getPIPs().addAll(allPIPs);
        return distLines;
    }

    /**
     * Routes from distribution lines to the leaf clock buffers (LCBs)
     * @param clk The current clock net
     * @param distLines A map of target clock regions and their respective horizontal distribution lines
     * @param lcbTargets The target LCB nodes to route the clock
     */
    public static void routeDistributionToLCBs(Net clk, Map<ClockRegion, Node> distLines,
            Map<Node, List<SitePinInst>> lcbTargets, Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints = getStartingPoints(distLines);
        routeToLCBs(clk, startingPoints, lcbTargets, getNodeStatus);
    }

    public static Map<ClockRegion, Set<NodeWithPrevAndCost>> getStartingPoints(Map<ClockRegion, Node> distLines) {
        Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints = new HashMap<>();
        for (Entry<ClockRegion, Node> e : distLines.entrySet()) {
            ClockRegion cr = e.getKey();
            Node distLine = e.getValue();
            startingPoints.computeIfAbsent(cr, k -> new HashSet<>())
                    .add(new NodeWithPrevAndCost(distLine));
        }
        return startingPoints;
    }

    public static void routeToLCBs(Net clk, Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints,
            Map<Node, List<SitePinInst>> lcbTargets, Function<Node, NodeStatus> getNodeStatus) {
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();

        nextLCB: for (Entry<Node, List<SitePinInst>> e : lcbTargets.entrySet()) {
            Node lcb = e.getKey();
            q.clear();
            visited.clear();
            Tile lcbTile = lcb.getTile();
            ClockRegion currCR = lcbTile.getClockRegion();
            Set<NodeWithPrevAndCost> starts = startingPoints.getOrDefault(currCR, Collections.emptySet());
            for (NodeWithPrev n : starts) {
                assert(n.getPrev() == null);
            }
            q.addAll(starts);
            while (!q.isEmpty()) {
                NodeWithPrevAndCost curr = q.poll();
                if (getNodeStatus.apply(curr) != NodeStatus.AVAILABLE) {
                    continue;
                }  

                if (lcb.equals(curr)) {
                    List<Node> path = curr.getPrevPath();
                    allPIPs.addAll(RouterHelper.getPIPsFromNodes(path));

                    Set<NodeWithPrevAndCost> s = startingPoints.get(currCR);
                    for (Node n : path) {
                        s.add(new NodeWithPrevAndCost(n));
                    }

                    for (SitePinInst sink : e.getValue()) {
                        sink.setRouted(true);
                    }

                    continue nextLCB;
                }
                for (Node downhill : curr.getAllDownhillNodes()) {
                    // Stay in this clock region
                    if (!currCR.equals(downhill.getTile().getClockRegion())) {
                        continue;
                    }
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) {
                        continue;
                    }
                    if (downhill.getWireName().endsWith("_I_CASC_PIN") || downhill.getWireName().endsWith("_CLR_B_PIN")) {
                        continue;
                    }
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    int cost = downhill.getTile().getManhattanDistance(lcbTile) + curr.depth;
                    q.add(new NodeWithPrevAndCost(downhill, curr, cost));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to leaf clock buffer " + lcb);
        }
        clk.getPIPs().addAll(allPIPs);
    }

    /**
     * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
     * @param clk The clock net to be routed.
     * @param vroute The node to start the route.
     * @param clockRegions Target clock regions.
     * @param down To indicate if it is routing to the group of top clock regions.
     * @return The map of target clock regions and their respective horizontal distribution lines.
     */
    public static Map<ClockRegion, Node> routeToHorizontalDistributionLines(Net clk,
                                                                            Node vroute,
                                                                            Collection<ClockRegion> clockRegions,
                                                                            boolean down,
                                                                            Function<Node, NodeStatus> getNodeStatus) {
        // First step: map each clock region to a VDISTR node. 
        // The clock region of this VDISTR node should be in the same column of the centroid (X) and the same row of the target clock region (Y). 
        Map<ClockRegion, Node> vertDistLines = routeVrouteToVerticalDistributionLines(clk, vroute, clockRegions, getNodeStatus);

        // Second step: start from the VDISTR node and try to find a HDISTR node in the target clock region.
        return routeVerticalToHorizontalDistributionLines(clk, vertDistLines, getNodeStatus);
    }

    /**
     * Routes a partially routed clock.
     * It will examine the clock net for SitePinInsts and assumes any present are already routed. It
     * then invokes {@link DesignTools#createMissingSitePinInsts(Design, Net)} to discover those not
     * yet routed.
     * @param design  The current design
     * @param clkNet The partially routed clock net to make fully routed
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Design design,
                                              Net clkNet,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // TODO:
        throw new RuntimeException("ERROR: Incremental clock routing not yet supported for Versal devices.");
    }

    /**
     * Routes a list of unrouted pins from a partially routed clock.
     * @param clkNet The partially routed clock net to make fully routed
     * @param clkPins A list of unrouted pins on the clock net to route
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Net clkNet,
                                              List<SitePinInst> clkPins,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // TODO:
        throw new RuntimeException("ERROR: Incremental clock routing not yet supported for Versal devices.");
    }

    public static Map<Node, List<SitePinInst>> routeLCBsToSinks(Net clk,
                                                                Function<Node,NodeStatus> getNodeStatus) {
        Map<Node, List<SitePinInst>> lcbMappings = new HashMap<>();
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_CLE_CNODE,
            IntentCode.NODE_INTF_CNODE,
            IntentCode.NODE_INODE,
            IntentCode.NODE_PINBOUNCE,
            IntentCode.NODE_CLE_BNODE,
            IntentCode.NODE_INTF_BNODE,
            IntentCode.NODE_IMUX,
            IntentCode.NODE_CLE_CTRL,
            IntentCode.NODE_INTF_CTRL,
            IntentCode.NODE_IRI,
            IntentCode.NODE_PINFEED,
            IntentCode.NODE_GLOBAL_LEAF
        );
        Set<Node> visited = new HashSet<>();
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        Predicate<Node> isNodeUnavailable = (node) -> getNodeStatus.apply(node) == NodeStatus.UNAVAILABLE;
        RouteThruHelper routeThruHelper = new RouteThruHelper(clk.getDesign().getDevice());

        nextPin: for (SitePinInst p: clk.getPins()) {
            if (p.isOutPin() || p.isRouted() || Utils.isIOB(p.getSiteInst())) {
                continue;
            }
            NodeWithPrev sink = new NodeWithPrev(p.getConnectedNode());
            ClockRegion cr = p.getTile().getClockRegion();
            boolean crossCRSink = Utils.isPS(p.getSiteInst()) || Utils.isNOC(p.getSiteInst());
            q.clear();
            q.add(sink);

            while (!q.isEmpty()) {
                NodeWithPrev curr = q.poll();
                for (Node uphill : curr.getAllUphillNodes()) {
                    if (!crossCRSink && !uphill.getTile().getClockRegion().equals(cr)) {
                        continue;
                    }
                    IntentCode uphillIntentCode = uphill.getIntentCode();
                    if (!allowedIntentCodes.contains(uphillIntentCode)) {
                        continue;
                    }
                    if (!visited.add(uphill)) {
                        continue;
                    }
                    if (routeThruHelper.isRouteThru(uphill, curr) && curr.getIntentCode() != IntentCode.NODE_IRI) {
                        continue;
                    }
                    if (isNodeUnavailable.test(uphill)) {
                        continue;
                    }
                    NodeWithPrev node = new NodeWithPrev(uphill, curr);
                    if (uphillIntentCode == IntentCode.NODE_GLOBAL_LEAF) {
                        List<Node> path = node.getPrevPath();
                        boolean srcToSinkOrder = true;
                        clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path, srcToSinkOrder));
                        lcbMappings.computeIfAbsent(uphill, (k) -> new ArrayList<>()).add(p);
                        visited.clear();
                        continue nextPin;
                    }
                    q.add(node);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route pin " + sink + " to any LCB");
        }

        return lcbMappings;
    }

    public static void routeNonLCBPins(Net clk, List<SitePinInst> sinks,
            Function<Node, NodeStatus> getNodeStatus) {
        Set<Node> visited = new HashSet<>();
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        Set<PIP> allPIPs = new HashSet<>(clk.getPIPs());
        nextPin: for (SitePinInst p : sinks) {
            q.clear();
            visited.clear();
            NodeWithPrev sink = new NodeWithPrev(p.getConnectedNode());
            List<Node> uphill = sink.getAllUphillNodes();
            boolean sinkOneHopLater = false;
            if (uphill.size() == 1) {
                sink = new NodeWithPrev(uphill.get(0));
                sinkOneHopLater = true;
            }
            for (PIP pip : allPIPs) {
                int cost = pip.getTile().getManhattanDistance(sink.getTile());
                q.add(new NodeWithPrevAndCost(pip.getEndNode(), null, cost));
            }

            while (!q.isEmpty()) {
                NodeWithPrevAndCost curr = q.poll();
                // Only block nodes used by other nets; nodes that are INUSE (already
                // preserved/used by this same clock net) must remain traversable so
                // that sinks projecting onto such a node -- e.g. an off-chip clock
                // output whose dedicated uphill node was preserved by this net -- can
                // still be reached. This mirrors routeLCBsToSinks(), which similarly
                // treats only UNAVAILABLE as blocking.
                if (getNodeStatus.apply(curr) == NodeStatus.UNAVAILABLE) {
                    continue;
                }
                visited.add(curr);
                if (sink.equals(curr)) {
                    if (sinkOneHopLater) {
                        curr = new NodeWithPrevAndCost(p.getConnectedNode(), curr, curr.cost + 1);
                    }
                    List<Node> path = curr.getPrevPath();
                    allPIPs.addAll(RouterHelper.getPIPsFromNodes(path));
                    p.setRouted(true);
                    continue nextPin;
                }

                for (Node downhill : curr.getAllDownhillNodes()) {
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    int cost = downhill.getTile().getManhattanDistance(sink.getTile()) + curr.depth;
                    q.add(new NodeWithPrevAndCost(downhill, curr, cost));
                }
            }

            System.out.println("CRITICAL WARNING: Unroutable pin " + p.getSitePinName() + " on clock net " + clk);
        }
        clk.getPIPs().addAll(allPIPs);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Map<Integer, VersalClockTree>> readVersalVDistrTrees() {
        InputStream is = FileTools.getRapidWrightResourceInputStream(FileTools.VERSAL_VDISTR_TREES_FILE_NAME);
        return (Map<String, Map<Integer, VersalClockTree>>) FileTools.readObjectFromKryoFile(is);
    }

    public static void writeVersalVDistrTreesFile() {
        String fileName = FileTools.getRapidWrightResourceFileName(FileTools.VERSAL_VDISTR_TREES_FILE_NAME);
        if (vdistrTrees == null) {
            throw new RuntimeException("ERROR: Cannot write file '" + fileName + "', source map is null.");
        }
        FileTools.writeObjectToKryoFile(fileName, vdistrTrees);
    }

    public static VersalClockTree getVersalClockTree(Device device, int minY, int maxY) {
        return getVersalClockTree(device, minY, maxY, VTreeType.BALANCED);
    }

    public static VersalClockTree getVersalClockTree(Device device, int minY, int maxY, VTreeType vtreeType) {
        Map<Integer, VersalClockTree> map = getDeviceVDistrTrees(device.getName());
        return map == null ? null : map.get(VersalClockTree.getMinMaxYRangeKey(minY, maxY, vtreeType));
    }

    public static VersalClockTree getVersalClockTree(Net clk, Device device, int minY, int maxY) {
        return getVersalClockTree(device, minY, maxY, getUserClockVTreeType(clk));
    }

    private static Map<Integer, VersalClockTree> getDeviceVDistrTrees(String deviceName) {
        if (vdistrTrees == null) {
            vdistrTrees = readVersalVDistrTrees();
        }
        return vdistrTrees.get(deviceName);
    }

    /**
     * Given a range of occupied clock region Y coordinates, get the preferred clock
     * root Y coordinate.
     * 
     * @param device The current device to target.
     * @param minY   The smallest Y coordinate of the clock region range.
     * @param maxY   The largest Y coordinate of the clock region range.
     * @return The preferred clock region Y coordinate for the given range or null
     *         if none could be found.
     */
    public static Integer getPreferredClockRootYCoord(Device device, int minY, int maxY) {
        VersalClockTree clkTree = getVersalClockTree(device, minY, maxY);
        return clkTree == null ? null : clkTree.getPreferredClockRootYCoord();
    }

    public static Integer getPreferredClockRootYCoord(Device device, int minY, int maxY, VTreeType vtreeType) {
        VersalClockTree clkTree = getVersalClockTree(device, minY, maxY, vtreeType);
        return clkTree == null ? null : clkTree.getPreferredClockRootYCoord();
    }

    public static Integer getPreferredClockRootYCoord(Net clk, Device device, int minY, int maxY) {
        return getPreferredClockRootYCoord(device, minY, maxY, getUserClockVTreeType(clk));
    }

    public static VTreeType getUserClockVTreeType(Net clk) {
        if (clk == null || clk.getLogicalNet() == null) {
            return VTreeType.BALANCED;
        }
        EDIFPropertyValue prop = clk.getLogicalNet().getProperty(USER_CLOCK_VTREE_TYPE);
        return prop == null ? VTreeType.BALANCED : VTreeType.fromVivadoName(prop.getValue());
    }
}
