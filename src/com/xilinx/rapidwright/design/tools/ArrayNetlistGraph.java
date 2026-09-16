/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.design.tools;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlockSide;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.Pair;
import org.jgrapht.Graph;
import org.jgrapht.GraphPath;
import org.jgrapht.alg.cycle.CycleDetector;
import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A graph providing an abstract representation of a netlist comprised of blackbox cells.
 * Used by ArrayBuilder to calculate an ideal placement for a netlist that minimizes the distance between
 * nearest neighbors: the grid position of every instance such that the longest connection, measured as
 * the Manhattan distance between the two instances it joins, is as short as possible.
 * <p>
 * Edges carry the side of the kernel PBlock the driving port sits on (the component's side map), which
 * says where the driven instance belongs relative to the driver. {@link #getPlacementGrid()} uses that:
 * when every instance is reachable over labeled edges the grid follows from propagating the directions
 * (exact, linear time, and the optimum whenever the netlist is a grid), otherwise the directions become
 * sign constraints solved per axis by longest path. Only netlists neither of those can orient go to the
 * CP-SAT model of {@link #getOptimalPlacementGrid}, and an unlabeled acyclic netlist keeps the legacy
 * greedy topological placement of {@link #getGreedyPlacementGrid}.
 */
public class ArrayNetlistGraph {
    public static class IdealArrayPlacement {
        private Map<Pair<Integer, Integer>, String> placementMap;
        private Map<String, Pair<Integer, Integer>> reversePlacementMap;
        private List<Pair<Pair<Integer, Integer>, String>> rowColumnOrder;
        private Integer arrayWidth;
        private Integer arrayHeight;
        IdealArrayPlacement() {
            placementMap = new HashMap<>();
            reversePlacementMap = new HashMap<>();
            rowColumnOrder = null;
            arrayWidth = null;
            arrayHeight = null;
        }

        public void place(String inst, int x, int y) {
            place(inst, new Pair<>(x, y));
        }

        public void place(String inst, Pair<Integer, Integer> loc) {
            placementMap.put(loc, inst);
            reversePlacementMap.put(inst, loc);
            rowColumnOrder = null;
            arrayWidth = null;
            arrayHeight = null;
        }

        public Pair<Integer, Integer> getPlacement(String inst) {
            return reversePlacementMap.get(inst);
        }

        public boolean placementExistsAtLocation(Pair<Integer, Integer> loc) {
            return placementMap.containsKey(loc);
        }

        public String getInstanceAtLocation(int x, int y) {
            return placementMap.get(new Pair<>(x, y));
        }

        public List<Pair<Pair<Integer, Integer>, String>> getRowColumnOrderList() {
            if (rowColumnOrder == null) {
                rowColumnOrder = placementMap.entrySet().stream()
                        .map((e) -> new Pair<>(e.getKey(), e.getValue()))
                        .sorted((p1, p2) -> {
                            Pair<Integer, Integer> pa = p1.getFirst();
                            Pair<Integer, Integer> pb = p2.getFirst();
                            if (!Objects.equals(pa.getSecond(), pb.getSecond())) {
                                return pa.getSecond().compareTo(pb.getSecond());
                            }

                            return pa.getFirst().compareTo(pb.getFirst());
                        })
                        .collect(Collectors.toList());
            }
            return rowColumnOrder;
        }

        public int getArrayWidth() {
            if (arrayWidth == null) {
                int maxXCoord = -1;
                for (Pair<Integer, Integer> loc : placementMap.keySet()) {
                    if (loc.getFirst() > maxXCoord) {
                        maxXCoord = loc.getFirst();
                    }
                }
                arrayWidth = maxXCoord + 1;
            }
            return arrayWidth;
        }

        public int getArrayHeight() {
            if (arrayHeight == null) {
                int maxYCoord = -1;
                for (Pair<Integer, Integer> loc : placementMap.keySet()) {
                    if (loc.getSecond() > maxYCoord) {
                        maxYCoord = loc.getSecond();
                    }
                }
                arrayHeight = maxYCoord + 1;
            }
            return arrayHeight;
        }
    }
    /**
     * Graph edge that contains an orthogonal direction from the provided side map.
     * Direction is used to pick a placement that improves routability.
     */
    private static class NetlistEdge extends DefaultEdge {
        private final PBlockSide direction;

        NetlistEdge(PBlockSide direction) {
            this.direction = direction;
        }

        public boolean noDirectionSpecified() {
            return direction == null;
        }

        public boolean isRight() {
            return direction == PBlockSide.RIGHT;
        }

        public boolean isLeft() {
            return direction == PBlockSide.LEFT;
        }

        public boolean isBelow() {
            return direction == PBlockSide.BOTTOM;
        }

        public boolean isAbove() {
            return direction == PBlockSide.TOP;
        }

        public PBlockSide getDirection() {
            return direction;
        }

        @Override
        public String toString() {
            return "(" + getSource() + " : " + getTarget() + ", " + this.direction + ")";
        }
    }
    private Graph<String, NetlistEdge> graph;
    private Map<Pair<String, String>, Boolean> directionMap;
    /** Swap LEFT and RIGHT when reading edge directions (the array is mirrored at placement time). */
    private boolean flipHorizontally = false;
    /** Wall-clock budget of the CP-SAT fallback, seconds. */
    private double cpSatTimeLimitSeconds = 120.0;

    public ArrayNetlistGraph() {
        graph = new DefaultDirectedGraph<>(NetlistEdge.class);
    }

    public ArrayNetlistGraph(Design array, List<String> modules) {
        this(array, modules, null);
    }

    public ArrayNetlistGraph(Design array, List<String> modules, Map<EDIFPort, PBlockSide> sideMap) {
        this();
        EDIFNetlist netlist = array.getNetlist();
        for (String module : modules) {
            addVertex(module);
        }

        for (String module : modules) {
            EDIFHierCellInst cellInst = netlist.getHierCellInstFromName(module);
            for (EDIFHierPortInst portInst : cellInst.getHierPortInsts()) {
                if (portInst.isOutput()) {
                    for (EDIFHierPortInst netPortInst : portInst.getHierarchicalNet().getPortInsts()) {
                        if (!netPortInst.equals(portInst) && netPortInst.getCellType() != null) {
                            EDIFHierCellInst destCellInst = netPortInst.getFullHierarchicalInst();
                            if (destCellInst != null && containsNode(destCellInst.getFullHierarchicalInstName())) {
                                PBlockSide pBlockSide = sideMap == null ? null : sideMap.get(portInst.getPortInst().getPort());

                                addEdge(cellInst.getFullHierarchicalInstName(),
                                        destCellInst.getFullHierarchicalInstName(),
                                        pBlockSide);
                            }
                        }
                    }
                }
            }
        }
    }

    public void addVertex(String name) {
        graph.addVertex(name);
    }

    public boolean containsNode(String name) {
        return graph.containsVertex(name);
    }

    public void addEdge(String from, String to, PBlockSide direction) {
        graph.addEdge(from, to, new NetlistEdge(direction));
    }

    public boolean isAcyclic() {
        CycleDetector<String, NetlistEdge> cycleDetector = new CycleDetector<>(graph);
        return !cycleDetector.detectCycles();
    }

    public Iterator<String> getTopologicalOrderIterator() {
        return new TopologicalOrderIterator<>(graph);
    }

    public IdealArrayPlacement getGreedyPlacementGrid() {
        IdealArrayPlacement idealPlacement = new IdealArrayPlacement();
        Map<String, Integer> candidateMap = new HashMap<>();
        Iterator<String> iterator = getTopologicalOrderIterator();
        DijkstraShortestPath<String, NetlistEdge> dsp = new DijkstraShortestPath<>(graph);
        String topLeftNode = iterator.next();
        idealPlacement.place(topLeftNode, 0, 0);
        for (NetlistEdge edge : graph.outgoingEdgesOf(topLeftNode)) {
            String node = graph.getEdgeTarget(edge);
            candidateMap.put(node, 1);
        }

        NetlistEdge extraConstraintEdge = graph.outgoingEdgesOf(topLeftNode).iterator().next();
        if (extraConstraintEdge.isRight() || extraConstraintEdge.isBelow()) {
            // Add additional constraint based on the sideMap
            String extraConstraintNode = graph.getEdgeTarget(extraConstraintEdge);
            candidateMap.remove(extraConstraintNode);
            Pair<Integer, Integer> extraConstraintPlacement;
            if (extraConstraintEdge.isRight()) {
                extraConstraintPlacement = new Pair<>(1, 0);
            } else {
                extraConstraintPlacement = new Pair<>(0, 1);
            }
            idealPlacement.place(extraConstraintNode, extraConstraintPlacement);
            for (NetlistEdge edge : graph.outgoingEdgesOf(extraConstraintNode)) {
                String targetNode = graph.getEdgeTarget(edge);
                int count = candidateMap.computeIfAbsent(targetNode, (n) -> 0);
                candidateMap.put(targetNode, count + 1);
            }
        }
        while (!candidateMap.isEmpty()) {
            List<String> sortedCandidates = candidateMap.entrySet().stream()
                    .sorted((e1, e2) -> {
                      if (e1.getValue() == e2.getValue()) {
                          // Tie-break of shorted path distance
                          GraphPath<String, NetlistEdge> shortestPathE1 = dsp.getPath(topLeftNode, e1.getKey());
                          GraphPath<String, NetlistEdge> shortestPathE2 = dsp.getPath(topLeftNode, e2.getKey());
                          return shortestPathE1.getLength() - shortestPathE2.getLength();
                      }
                      return e2.getValue().compareTo(e1.getValue());
                    })
                    .map(Map.Entry::getKey).collect(Collectors.toList());
            String node = sortedCandidates.get(0);
            candidateMap.remove(node);
            for (NetlistEdge edge : graph.outgoingEdgesOf(node)) {
                String targetNode = graph.getEdgeTarget(edge);
                int count = candidateMap.computeIfAbsent(targetNode, (n) -> 0);
                candidateMap.put(targetNode, count + 1);
            }
            Set<NetlistEdge> inEdges = graph.incomingEdgesOf(node);
            List<String> inNeighbors = new ArrayList<>();
            for (NetlistEdge e : inEdges) {
                inNeighbors.add(graph.getEdgeSource(e));
            }
            if (inNeighbors.size() > 3) {
                throw new RuntimeException("Greedy placement does not work for given netlist");
            }
            List<Pair<Integer, Integer>> inNeighborPlacements = new ArrayList<>();
            for (String inNeighbor : inNeighbors) {
                inNeighborPlacements.add(idealPlacement.getPlacement(inNeighbor));
            }
            inNeighborPlacements = inNeighborPlacements.stream().sorted(
                    (p1, p2) -> {
                        if (p1.getSecond().equals(p2.getSecond())) {
                            return p1.getFirst() - p2.getFirst();
                        }
                        return p1.getSecond() - p2.getSecond();
                    }).collect(Collectors.toList());
            List<Pair<Integer, Integer>> validPlacements = new ArrayList<>();
            if (inNeighbors.size() == 1) {
                Pair<Integer, Integer> neighborPlacement = inNeighborPlacements.get(0);
                validPlacements.add(new Pair<>(neighborPlacement.getFirst() + 1, neighborPlacement.getSecond()));
                validPlacements.add(new Pair<>(neighborPlacement.getFirst(), neighborPlacement.getSecond() + 1));
            } else if (inNeighbors.size() == 2) {
                int x = inNeighborPlacements.get(0).getFirst();
                int y = inNeighborPlacements.get(1).getSecond();
                validPlacements.add(new Pair<>(x, y));
            } else {
                throw new RuntimeException("Not yet implemented, try using OR-tools based placement");
            }
            Pair<Integer, Integer> placement = null;
            for (Pair<Integer, Integer> location : validPlacements) {
                if (!idealPlacement.placementExistsAtLocation(location)) {
                    placement = location;
                }
            }
            if (placement == null) {
                throw new RuntimeException("Could not find valid greedy placement for cell: " + node);
            }
            idealPlacement.place(node, placement);
        }

        for (int y = 0; y < graph.vertexSet().size(); y++) {
            for (int x = 0; x < graph.vertexSet().size(); x++) {
                if (idealPlacement.placementExistsAtLocation(new Pair<>(x, y))) {
                    System.out.println("Placed " + idealPlacement.getInstanceAtLocation(x, y) + " at (" + x + ", " + y + ")");
                }
            }
        }

        return idealPlacement;
    }

    public void setFlipHorizontally(boolean flipHorizontally) {
        this.flipHorizontally = flipHorizontally;
    }

    public void setCpSatTimeLimitSeconds(double seconds) {
        this.cpSatTimeLimitSeconds = seconds;
    }

    /** Grid step of an edge direction: x grows to the right, y grows downward. */
    private int[] delta(PBlockSide side) {
        switch (side) {
            case TOP:    return new int[] {0, -1};
            case BOTTOM: return new int[] {0, 1};
            case RIGHT:  return new int[] {flipHorizontally ? -1 : 1, 0};
            case LEFT:   return new int[] {flipHorizontally ? 1 : -1, 0};
            default:     throw new IllegalArgumentException(String.valueOf(side));
        }
    }

    /** Longest Manhattan distance over all edges of {@code placement}, with the edge count. */
    private int[] maxStretch(IdealArrayPlacement placement) {
        int max = 0, edges = 0;
        for (NetlistEdge e : graph.edgeSet()) {
            Pair<Integer, Integer> a = placement.getPlacement(graph.getEdgeSource(e));
            Pair<Integer, Integer> b = placement.getPlacement(graph.getEdgeTarget(e));
            if (a == null || b == null) continue;
            max = Math.max(max, Math.abs(a.getFirst() - b.getFirst()) + Math.abs(a.getSecond() - b.getSecond()));
            edges++;
        }
        return new int[] {max, edges};
    }

    private static IdealArrayPlacement normalized(Map<String, int[]> coords) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (int[] c : coords.values()) { minX = Math.min(minX, c[0]); minY = Math.min(minY, c[1]); }
        IdealArrayPlacement p = new IdealArrayPlacement();
        for (Map.Entry<String, int[]> e : coords.entrySet()) p.place(e.getKey(), e.getValue()[0] - minX, e.getValue()[1] - minY);
        return p;
    }

    private static boolean hasCollision(Map<String, int[]> coords) {
        Set<Pair<Integer, Integer>> seen = new HashSet<>();
        for (int[] c : coords.values()) if (!seen.add(new Pair<>(c[0], c[1]))) return true;
        return false;
    }

    private void printGrid(IdealArrayPlacement placement) {
        for (Pair<Pair<Integer, Integer>, String> e : placement.getRowColumnOrderList()) {
            System.out.println("Placed " + e.getSecond() + " at (" + e.getFirst().getFirst() + ", " + e.getFirst().getSecond() + ")");
        }
    }

    /**
     * The ideal placement of this netlist, by the first of these that applies: the propagation of
     * the edge directions when they place every instance consistently (see the class comment), the
     * per-axis longest-path compaction of the direction signs, the legacy greedy grid for an
     * acyclic netlist without directions, and last the CP-SAT model, hinted with the best partial
     * answer of the steps before it.
     */
    public IdealArrayPlacement getPlacementGrid() {
        int labeled = 0, unlabeled = 0;
        for (NetlistEdge e : graph.edgeSet()) { if (e.noDirectionSpecified()) unlabeled++; else labeled++; }
        System.out.println("[ArrayNetlistGraph] " + graph.vertexSet().size() + " instances, " + labeled
                + " directed and " + unlabeled + " undirected connections" + (flipHorizontally ? " (mirrored)" : ""));
        if (graph.vertexSet().size() == 1) {
            IdealArrayPlacement p = new IdealArrayPlacement();
            p.place(graph.vertexSet().iterator().next(), 0, 0);
            return p;
        }
        Map<String, int[]> hint = null;
        if (labeled > 0) {
            Map<String, int[]> coords = propagateDirections();
            if (coords != null) {
                IdealArrayPlacement p = normalized(coords);
                int[] st = maxStretch(p);
                System.out.println("[ArrayNetlistGraph] direction propagation placed the " + p.getArrayWidth() + " x "
                        + p.getArrayHeight() + " grid; longest connection " + st[0] + " over " + st[1] + " connections");
                printGrid(p);
                return p;
            }
            coords = compactDirections();
            if (coords != null) {
                if (!hasCollision(coords)) {
                    IdealArrayPlacement p = normalized(coords);
                    int[] st = maxStretch(p);
                    System.out.println("[ArrayNetlistGraph] longest-path compaction placed the " + p.getArrayWidth() + " x "
                            + p.getArrayHeight() + " grid; longest connection " + st[0] + " over " + st[1] + " connections");
                    printGrid(p);
                    return p;
                }
                System.out.println("[ArrayNetlistGraph] longest-path compaction leaves instances on the same cell; used as the CP-SAT hint");
                hint = coords;
            }
        } else if (isAcyclic()) {
            System.out.println("[ArrayNetlistGraph] no directions and an acyclic netlist: greedy placement");
            return getGreedyPlacementGrid();
        }
        System.out.println("[ArrayNetlistGraph] the directions do not orient the netlist; solving with CP-SAT");
        return getOptimalPlacementGrid(hint);
    }

    /**
     * Tier 1a: breadth-first propagation of unit offsets over the labeled edges (each traversed in
     * both directions), from an arbitrary root. Returns null when some instance is not reachable
     * over labeled edges, when two paths disagree on an instance's position, or when two instances
     * land on the same cell; any of those means the netlist is not a plain grid.
     */
    private Map<String, int[]> propagateDirections() {
        Map<String, int[]> coords = new LinkedHashMap<>();
        String root = graph.vertexSet().iterator().next();
        coords.put(root, new int[] {0, 0});
        Deque<String> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            String u = queue.poll();
            int[] cu = coords.get(u);
            for (NetlistEdge e : graph.edgesOf(u)) {
                if (e.noDirectionSpecified()) continue;
                boolean forward = graph.getEdgeSource(e).equals(u);
                String v = forward ? graph.getEdgeTarget(e) : graph.getEdgeSource(e);
                if (v.equals(u)) continue;
                int[] d = delta(e.getDirection());
                int[] cv = forward ? new int[] {cu[0] + d[0], cu[1] + d[1]} : new int[] {cu[0] - d[0], cu[1] - d[1]};
                int[] have = coords.get(v);
                if (have == null) { coords.put(v, cv); queue.add(v); }
                else if (have[0] != cv[0] || have[1] != cv[1]) {
                    System.out.println("[ArrayNetlistGraph] " + v + " is at (" + have[0] + "," + have[1] + ") but its connection from "
                            + u + " (" + e.getDirection() + ") wants (" + cv[0] + "," + cv[1] + "): not a unit grid");
                    return null;
                }
            }
        }
        if (coords.size() != graph.vertexSet().size()) {
            System.out.println("[ArrayNetlistGraph] only " + coords.size() + " of " + graph.vertexSet().size()
                    + " instances are reachable over directed connections");
            return null;
        }
        if (hasCollision(coords)) {
            System.out.println("[ArrayNetlistGraph] direction propagation puts two instances on one cell");
            return null;
        }
        return coords;
    }

    /**
     * Tier 1b: the directions as signs only. Instances joined by a vertical connection share a
     * column, a horizontal connection orders its two columns; the columns are then packed by longest
     * path so every ordering is satisfied with the least spread. The same for rows. Returns null when
     * the orderings contain a cycle; the result may put two instances on one cell.
     */
    private Map<String, int[]> compactDirections() {
        Map<String, Integer> x = compactAxis(true), y = compactAxis(false);
        if (x == null || y == null) return null;
        Map<String, int[]> coords = new LinkedHashMap<>();
        for (String v : graph.vertexSet()) coords.put(v, new int[] {x.get(v), y.get(v)});
        return coords;
    }

    private Map<String, Integer> compactAxis(boolean xAxis) {
        // union-find over the instances that share a coordinate on this axis
        Map<String, String> parent = new HashMap<>();
        for (String v : graph.vertexSet()) parent.put(v, v);
        java.util.function.Function<String, String> find = new java.util.function.Function<String, String>() {
            public String apply(String v) { while (!parent.get(v).equals(v)) { parent.put(v, parent.get(parent.get(v))); v = parent.get(v); } return v; }
        };
        for (NetlistEdge e : graph.edgeSet()) {
            if (e.noDirectionSpecified()) continue;
            int[] d = delta(e.getDirection());
            boolean movesOnAxis = xAxis ? d[0] != 0 : d[1] != 0;
            if (!movesOnAxis) parent.put(find.apply(graph.getEdgeSource(e)), find.apply(graph.getEdgeTarget(e)));
        }
        // the ordering constraints between classes: succ(a) contains b when b must be at least a + 1
        Map<String, Set<String>> succ = new HashMap<>();
        Map<String, Integer> indeg = new HashMap<>();
        for (String v : graph.vertexSet()) { String r = find.apply(v); succ.putIfAbsent(r, new HashSet<>()); indeg.putIfAbsent(r, 0); }
        for (NetlistEdge e : graph.edgeSet()) {
            if (e.noDirectionSpecified()) continue;
            int[] d = delta(e.getDirection());
            int step = xAxis ? d[0] : d[1];
            if (step == 0) continue;
            String a = find.apply(graph.getEdgeSource(e)), b = find.apply(graph.getEdgeTarget(e));
            if (a.equals(b)) {
                System.out.println("[ArrayNetlistGraph] " + graph.getEdgeSource(e) + " and " + graph.getEdgeTarget(e)
                        + " must share a " + (xAxis ? "column" : "row") + " and also be ordered on it");
                return null;
            }
            String lo = step > 0 ? a : b, hi = step > 0 ? b : a;
            if (succ.get(lo).add(hi)) indeg.merge(hi, 1, Integer::sum);
        }
        // longest path in topological order
        Map<String, Integer> pos = new HashMap<>();
        Deque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> e : indeg.entrySet()) if (e.getValue() == 0) { ready.add(e.getKey()); pos.put(e.getKey(), 0); }
        int done = 0;
        while (!ready.isEmpty()) {
            String a = ready.poll(); done++;
            for (String b : succ.get(a)) {
                pos.merge(b, pos.get(a) + 1, Math::max);
                if (indeg.merge(b, -1, Integer::sum) == 0) ready.add(b);
            }
        }
        if (done != indeg.size()) {
            System.out.println("[ArrayNetlistGraph] the " + (xAxis ? "horizontal" : "vertical") + " orderings contain a cycle");
            return null;
        }
        Map<String, Integer> result = new HashMap<>();
        for (String v : graph.vertexSet()) result.put(v, pos.get(find.apply(v)));
        return result;
    }

    /**
     * Tier 2: CP-SAT over integer coordinates. One x and one y per instance, all cells distinct, the
     * longest Manhattan distance over the edges minimized directly, edge directions kept as sign
     * constraints, translation fixed by pinning the smallest x and y to 0, and {@code hint} (if any)
     * as the starting point. Runs for at most {@link #setCpSatTimeLimitSeconds} and accepts a
     * feasible answer. For a grid netlist this is the slow path; the propagation above is exact
     * there and never gets here.
     */
    public IdealArrayPlacement getOptimalPlacementGrid(Map<String, int[]> hint) {
        int n = graph.vertexSet().size();
        List<String> names = new ArrayList<>(new java.util.TreeSet<>(graph.vertexSet()));
        Map<String, Integer> num = new HashMap<>();
        for (int i = 0; i < n; i++) num.put(names.get(i), i);

        Loader.loadNativeLibraries();
        CpModel model = new CpModel();
        IntVar[] x = new IntVar[n], y = new IntVar[n];
        LinearArgument[] cells = new LinearArgument[n];
        for (int i = 0; i < n; i++) {
            x[i] = model.newIntVar(0, n - 1, "x" + i);
            y[i] = model.newIntVar(0, n - 1, "y" + i);
            cells[i] = LinearExpr.weightedSum(new LinearArgument[] {x[i], y[i]}, new long[] {n, 1});
        }
        model.addAllDifferent(cells);
        model.addMinEquality(LinearExpr.constant(0), x);
        model.addMinEquality(LinearExpr.constant(0), y);
        IntVar longest = model.newIntVar(0, 2L * (n - 1), "longest");
        int k = 0;
        for (NetlistEdge e : graph.edgeSet()) {
            int a = num.get(graph.getEdgeSource(e)), b = num.get(graph.getEdgeTarget(e));
            if (a == b) continue;
            IntVar dx = model.newIntVar(0, n - 1, "dx" + k), dy = model.newIntVar(0, n - 1, "dy" + k);
            k++;
            model.addAbsEquality(dx, LinearExpr.weightedSum(new LinearArgument[] {x[a], x[b]}, new long[] {1, -1}));
            model.addAbsEquality(dy, LinearExpr.weightedSum(new LinearArgument[] {y[a], y[b]}, new long[] {1, -1}));
            model.addLessOrEqual(LinearExpr.sum(new LinearArgument[] {dx, dy}), longest);
            if (!e.noDirectionSpecified()) {
                int[] d = delta(e.getDirection());
                if (d[0] != 0) model.addGreaterOrEqual(LinearExpr.weightedSum(new LinearArgument[] {x[b], x[a]}, new long[] {d[0], -d[0]}), 1);
                if (d[1] != 0) model.addGreaterOrEqual(LinearExpr.weightedSum(new LinearArgument[] {y[b], y[a]}, new long[] {d[1], -d[1]}), 1);
            }
        }
        model.minimize(longest);
        if (hint != null) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            for (int[] c : hint.values()) { minX = Math.min(minX, c[0]); minY = Math.min(minY, c[1]); }
            for (Map.Entry<String, int[]> h : hint.entrySet()) {
                Integer i = num.get(h.getKey());
                if (i == null) continue;
                model.addHint(x[i], Math.min(n - 1, h.getValue()[0] - minX));
                model.addHint(y[i], Math.min(n - 1, h.getValue()[1] - minY));
            }
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(cpSatTimeLimitSeconds);
        solver.getParameters().setNumWorkers(8);
        long t0 = System.currentTimeMillis();
        CpSolverStatus status = solver.solve(model);
        if (status != CpSolverStatus.FEASIBLE && status != CpSolverStatus.OPTIMAL) {
            throw new RuntimeException("Failed to find a placement grid, CP-SAT returned " + status + " after "
                    + (System.currentTimeMillis() - t0) + " ms for " + n + " instances and " + k + " connections");
        }
        Map<String, int[]> coords = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) coords.put(names.get(i), new int[] {(int) solver.value(x[i]), (int) solver.value(y[i])});
        IdealArrayPlacement p = normalized(coords);
        System.out.println("[ArrayNetlistGraph] CP-SAT " + status + " in " + (System.currentTimeMillis() - t0) + " ms: "
                + p.getArrayWidth() + " x " + p.getArrayHeight() + " grid, longest connection " + solver.value(longest));
        printGrid(p);
        return p;
    }

    @Override
    public String toString() {
        return graph.toString();
    }
}
