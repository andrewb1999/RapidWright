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
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Versal interconnect delay from the nodes a connection actually uses.
 *
 * <p>Each node type has a characterized delay at each corner; a connection's
 * delay is the sum over the nodes on its route from source to sink. The table
 * is fitted against Vivado's per-sink net delays by least squares, and the
 * per-node values are not individually meaningful — several types only ever
 * appear together, so the fit splits their shared delay between them
 * arbitrarily — but the sums along real routes are.
 *
 * <p>This needs the net routed: an unrouted connection has no nodes to sum
 * and is reported as unknown rather than estimated.
 *
 * <p>The table file has one section:
 * <pre>
 * node_delay
 * NODE_HSINGLE 12.34 10.20
 * ...
 * BRANCHES_PASSED 7.2 2.7
 * NET_FANOUT 0.3 0.2
 * </pre>
 * with the intent code name followed by the slow-max and slow-min delay in
 * picoseconds. A line with one delay applies it to both corners. The two
 * named rows are load terms rather than node types: per extra branch a node
 * on the route fans out to, and per sink of the net.
 */
public class VersalInterconnectDelayModel implements InterconnectDelayModel {

    /** Where the table lives relative to the RapidWright installation. */
    public static final String DEFAULT_FILE = TimingModel.TIMING_DATA_DIR + "/versal/node_delay.txt";

    /** Table row naming the per-branch term: each extra child a node on the route fans out to. */
    public static final String BRANCH_TERM = "BRANCHES_PASSED";
    /** Table row naming the per-sink term: the net's total sink count. */
    public static final String FANOUT_TERM = "NET_FANOUT";

    private final Map<IntentCode, float[]> delays = new EnumMap<>(IntentCode.class);
    private float[] branchPs = { 0, 0 };
    private float[] fanoutPs = { 0, 0 };
    private final Map<Net, Routing> routingCache = new HashMap<>();

    /** A net's routed tree: how each node was reached, and how many children each has. */
    private static class Routing {
        final Map<Node, Node> parent;
        final Map<Node, Integer> children = new HashMap<>();
        final int sinks;

        Routing(Map<Node, Node> parent, int sinks) {
            this.parent = parent;
            this.sinks = sinks;
            for (Map.Entry<Node, Node> e : parent.entrySet()) {
                if (e.getValue() != null) {
                    children.merge(e.getValue(), 1, Integer::sum);
                }
            }
        }
    }

    public VersalInterconnectDelayModel(Path table) throws IOException {
        read(table);
    }

    /** Loads the table from its default location in the RapidWright installation. */
    public static VersalInterconnectDelayModel load() throws IOException {
        String file = FileTools.getRapidWrightResourceFileName(DEFAULT_FILE);
        if (file == null) {
            throw new IOException("Cannot locate " + DEFAULT_FILE + ": RAPIDWRIGHT_PATH is not set");
        }
        return new VersalInterconnectDelayModel(Paths.get(file));
    }

    private void read(Path table) throws IOException {
        boolean inSection = false;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.equals("node_delay")) {
                inSection = true;
                continue;
            }
            if (!inSection) {
                continue;
            }
            String[] f = t.split("\\s+");
            if (f.length < 2) {
                continue;
            }
            float max = Float.parseFloat(f[1]);
            float min = f.length > 2 ? Float.parseFloat(f[2]) : max;
            if (f[0].equals(BRANCH_TERM)) {
                branchPs = new float[] { max, min };
                continue;
            }
            if (f[0].equals(FANOUT_TERM)) {
                fanoutPs = new float[] { max, min };
                continue;
            }
            IntentCode code;
            try {
                code = IntentCode.valueOf(f[0]);
            } catch (IllegalArgumentException e) {
                // A type this RapidWright build does not know; skip it.
                continue;
            }
            delays.put(code, new float[] { max, min });
        }
        if (delays.isEmpty()) {
            throw new IOException("No node_delay entries in " + table);
        }
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.SIGNOFF;
    }

    /** The characterized delay of one node type, or null if the type is not in the table. */
    public Float getNodeDelayPs(IntentCode code, Corner corner) {
        float[] d = delays.get(code);
        return d == null ? null : d[corner == Corner.SLOW_MIN ? 1 : 0];
    }

    /**
     * Forgets cached routing for a net. Call after rerouting a net that was
     * priced before, since the route is memoized per net.
     */
    public void invalidate(Net net) {
        routingCache.remove(net);
    }

    public void clearCache() {
        routingCache.clear();
    }

    /**
     * The sum of the route's node delays, plus a term for each extra child
     * that nodes along the route fan out to and one for the net's sink
     * count: a node driving several branches is slower than the same node on
     * a point-to-point route, and the fit accounts for it the same way.
     */
    @Override
    public Float getNetDelayPs(Net net, SitePinInst sink, Corner corner) {
        Routing routing = getRouting(net);
        List<Node> route = routing == null ? null : routeTo(routing, sink);
        if (route == null) {
            return null;
        }
        int idx = corner == Corner.SLOW_MIN ? 1 : 0;
        float sum = routing.sinks * fanoutPs[idx];
        for (Node n : route) {
            float[] d = delays.get(n.getIntentCode());
            if (d != null) {
                sum += d[idx];
            }
            int branches = routing.children.getOrDefault(n, 0) - 1;
            if (branches > 0) {
                sum += branches * branchPs[idx];
            }
        }
        return sum;
    }

    private Routing getRouting(Net net) {
        if (net.getSource() == null) {
            return null;
        }
        return routingCache.computeIfAbsent(net,
                n -> new Routing(buildParents(n), n.getSinkPins().size()));
    }

    private static List<Node> routeTo(Routing routing, SitePinInst sink) {
        Node end = sink == null ? null : sink.getConnectedNode();
        if (end == null || !routing.parent.containsKey(end)) {
            return null;
        }
        List<Node> route = new ArrayList<>();
        for (Node n = end; n != null; n = routing.parent.get(n)) {
            route.add(n);
        }
        return route;
    }

    /**
     * The nodes from the net's source to this sink, inclusive at both ends, in
     * sink-to-source order; or null if the sink is not reachable through the
     * net's PIPs.
     */
    public List<Node> getRoute(Net net, SitePinInst sink) {
        Routing routing = getRouting(net);
        return routing == null ? null : routeTo(routing, sink);
    }

    /**
     * Finds how each node was reached, searching forward from the net's
     * source. A reverse walk from the sink is not safe here: routed nets can
     * carry duplicate end nodes, and a reverse map would then pick an
     * arbitrary one.
     */
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
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            List<Node> next = downhill.get(n);
            if (next == null) {
                continue;
            }
            for (Node c : next) {
                if (seen.add(c)) {
                    parent.put(c, n);
                    queue.add(c);
                }
            }
        }
        return parent;
    }
}
