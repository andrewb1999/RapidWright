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

package com.xilinx.rapidwright.router;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.timing.ClkRouteTiming;

/**
 * Computes clock arrival and skew for a routed Versal clock net from
 * characterized data, so skew analysis does not require running Vivado timing
 * analysis.
 *
 * <p>Reproducing Vivado's {@code SKEW = ECD - SCD + CPR} needs three measured
 * pieces, because the quantity is not a simple per-sink lookup:
 * <ul>
 * <li>the per-sink clock net delay at the <em>maximum</em> corner, used on the
 * launch side;</li>
 * <li>the same at the <em>minimum</em> corner, used on the capture side (setup
 * analysis is deliberately asymmetric);</li>
 * <li>clock pessimism removal, which is a property of a <em>pair</em> of sinks —
 * it credits back the clock path they share — and is therefore keyed by the node
 * at which their routes diverge.</li>
 * </ul>
 * The first two are supplied as {@link ClkRouteTiming} files, one per corner;
 * the third as a table produced alongside them.
 *
 * <p>Against Vivado's reported skew over 2133 setup paths on a Versal systolic
 * array, this reproduces the value to a mean of 13ps and a worst case of 74ps
 * (about one {@link VersalClockDeskew} tap). Dropping the pessimism term
 * instead gives a mean error of 491ps, larger than the skew being measured.
 */
public class VersalClockTimingModel {

    /**
     * Input net plus BUFG cell delay in ps, which sits between the clock port
     * and the net delay the characterization measures. Differs by corner
     * because the cell delay does.
     */
    private final int bufgMaxPs;
    private final int bufgMinPs;

    /** Sink tile name -> clock net delay, per corner. */
    private final Map<String, Short> launchDelays;
    private final Map<String, Short> captureDelays;
    /** "setup"/"hold" plus divergence node -> pessimism in ps. */
    private final Map<String, Integer> cpr;

    /** Where the clock arrives, and by what route, for each sink site. */
    private final Map<Site, String> siteTile = new HashMap<>();
    private final Map<Site, List<Node>> siteRoute = new HashMap<>();

    public static final int DEFAULT_BUFG_MAX_PS = 146;
    public static final int DEFAULT_BUFG_MIN_PS = 120;

    public VersalClockTimingModel(Net clk, Path maxDelayFile, Path minDelayFile, Path cprFile)
            throws IOException {
        this(clk, maxDelayFile, minDelayFile, cprFile, DEFAULT_BUFG_MAX_PS, DEFAULT_BUFG_MIN_PS);
    }

    public VersalClockTimingModel(Net clk, Path maxDelayFile, Path minDelayFile, Path cprFile,
                                  int bufgMaxPs, int bufgMinPs) throws IOException {
        this.bufgMaxPs = bufgMaxPs;
        this.bufgMinPs = bufgMinPs;
        this.launchDelays = new ClkRouteTiming(maxDelayFile.toString())
                .getRouteDelaysToSinkINTTiles();
        this.captureDelays = new ClkRouteTiming(minDelayFile.toString())
                .getRouteDelaysToSinkINTTiles();
        this.cpr = cprFile == null ? new HashMap<>() : readCpr(cprFile);
        indexClockSinks(clk);
    }

    /**
     * Records, for every sink of the routed clock, the tile the delay tables
     * are keyed on and the route the pessimism lookup needs.
     */
    private void indexClockSinks(Net clk) {
        Map<Node, Node> parent = buildParents(clk);
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) {
                continue;
            }
            Tile t = getSinkTile(spi);
            if (t != null) {
                siteTile.put(spi.getSite(), t.getName());
            }
            Node sink = spi.getConnectedNode();
            if (sink == null || !parent.containsKey(sink)) {
                continue;
            }
            List<Node> route = new ArrayList<>();
            for (Node n = sink; n != null; n = parent.get(n)) {
                route.add(n);
            }
            Collections.reverse(route);
            siteRoute.put(spi.getSite(), route);
        }
    }

    /**
     * Finds how the clock reaches every node, by searching forward from the
     * source.
     *
     * <p>Walking backwards from each sink does not work: a Versal clock net is
     * not a clean tree in the checkpoint (some nodes are the endpoint of more
     * than one PIP), so a reverse map can contain cycles. Hops along the
     * vertical routing spine are not represented by PIPs at all, so the search
     * also steps to downhill neighbours belonging to the net.
     */
    private static Map<Node, Node> buildParents(Net clk) {
        Map<Node, List<Node>> downhill = new HashMap<>();
        Set<Node> netNodes = new HashSet<>();
        for (PIP p : clk.getPIPs()) {
            downhill.computeIfAbsent(p.getStartNode(), k -> new ArrayList<>()).add(p.getEndNode());
            netNodes.add(p.getStartNode());
            netNodes.add(p.getEndNode());
        }
        Map<Node, Node> parent = new HashMap<>();
        SitePinInst src = clk.getSource();
        if (src == null) {
            return parent;
        }
        Node sourceNode = src.getConnectedNode();
        if (sourceNode == null) {
            return parent;
        }
        Deque<Node> queue = new ArrayDeque<>();
        queue.add(sourceNode);
        parent.put(sourceNode, null);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            List<Node> next = downhill.get(n);
            if (next != null) {
                for (Node c : next) {
                    if (!parent.containsKey(c)) {
                        parent.put(c, n);
                        queue.add(c);
                    }
                }
            }
            for (Node c : n.getAllDownhillNodes()) {
                if (netNodes.contains(c) && !parent.containsKey(c)) {
                    parent.put(c, n);
                    queue.add(c);
                }
            }
        }
        return parent;
    }

    private static Tile getSinkTile(SitePinInst spi) {
        try {
            Tile t = RouterHelper.getUpstreamINTTileOfClkIn(spi);
            if (t != null) {
                return t;
            }
        } catch (RuntimeException e) {
            // Not an INT-tile architecture for this pin; fall through.
        }
        Node n = spi.getConnectedNode();
        return n == null ? null : n.getTile();
    }

    /** True if both sites have the data needed to compute a skew between them. */
    public boolean covers(Site launch, Site capture) {
        return launchDelay(launch) != null && captureDelay(capture) != null;
    }

    private Short launchDelay(Site s) {
        String tile = siteTile.get(s);
        return tile == null ? null : launchDelays.get(tile);
    }

    private Short captureDelay(Site s) {
        String tile = siteTile.get(s);
        return tile == null ? null : captureDelays.get(tile);
    }

    /**
     * Returns the clock skew between a launch and capture site, in picoseconds,
     * following Vivado's definition: capture arrival minus launch arrival, plus
     * the pessimism credited back for the path they share. A positive skew
     * favours setup and penalizes hold.
     *
     * @param launch  Site the path launches from.
     * @param capture Site the path is captured in.
     * @param setup   Whether to use the setup or hold pessimism table; the two
     *                analyses use opposite corner conventions and so credit
     *                back different amounts for the same shared path.
     * @return Skew in ps, or null if either site is not covered by the data.
     */
    public Integer getSkewPs(Site launch, Site capture, boolean setup) {
        Short l = launchDelay(launch);
        Short c = captureDelay(capture);
        if (l == null || c == null) {
            return null;
        }
        int scd = l + bufgMaxPs;
        int ecd = c + bufgMinPs;
        return ecd - scd + getCprPs(launch, capture, setup);
    }

    /**
     * Returns the pessimism credited for the clock path two sites share, in ps.
     * Falls back to zero when the divergence point is unknown, which is
     * conservative in the sense of reporting more skew than exists.
     */
    public int getCprPs(Site launch, Site capture, boolean setup) {
        List<Node> a = siteRoute.get(launch);
        List<Node> b = siteRoute.get(capture);
        if (a == null || b == null) {
            return 0;
        }
        Node div = divergenceNode(a, b);
        if (div == null) {
            return 0;
        }
        Integer v = cpr.get((setup ? "setup " : "hold ") + div);
        return v == null ? 0 : v;
    }

    /**
     * Returns the last node two routes have in common. Both run from the clock
     * source, so their common prefix is the shared clock path.
     */
    private static Node divergenceNode(List<Node> a, List<Node> b) {
        int n = Math.min(a.size(), b.size());
        Node last = null;
        for (int i = 0; i < n; i++) {
            if (!a.get(i).equals(b.get(i))) {
                break;
            }
            last = a.get(i);
        }
        return last;
    }

    private static Map<String, Integer> readCpr(Path file) throws IOException {
        Map<String, Integer> cpr = new HashMap<>();
        String section = null;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("cpr_")) {
                section = t.substring("cpr_".length());
                continue;
            }
            String[] f = t.split("\\s+");
            if (section == null || f.length < 2) {
                continue;
            }
            try {
                cpr.put(section + " " + f[0], Integer.parseInt(f[1]));
            } catch (NumberFormatException e) {
                // Not a data row.
            }
        }
        return cpr;
    }

    /** Number of clock sinks the delay tables cover. */
    public int getCoveredSiteCount() {
        int n = 0;
        for (Site s : siteTile.keySet()) {
            if (launchDelay(s) != null) {
                n++;
            }
        }
        return n;
    }
}
