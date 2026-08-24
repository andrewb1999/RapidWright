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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.util.FileTools;

/**
 * The per-node clock model: arrival and pessimism computed from whatever
 * clock route exists, with no per-design tables.
 *
 * <p>Arrival at a sink is the sum along its route of per-<em>instance</em>
 * terms for the trunk (spine and distribution nodes — each a fixed piece of
 * silicon, so a device constant) and per-<em>type</em> terms for the leaf
 * level, plus the buffer constant. Pessimism removal for a pair is the
 * max-minus-min corner spread of the route prefix the two share, priced by
 * type-by-tile-type terms — the featurization the pessimism check validated
 * at 17 ps with no divergence-node table at all.
 *
 * <p>A trunk instance the table has never seen falls back to its
 * type-by-tile term and is counted; full device coverage comes from the
 * clock trunk sweep, after which no per-design measurement remains.
 *
 * <p>Table sections ({@code clock_node_delay} — arrival terms,
 * {@code clock_pessimism_delay} — type-by-tile terms), columns
 * {@code key slow_max_ps slow_min_ps}, produced by the harness's
 * {@code ClkNodeFit}.
 */
public class VersalClockNodeModel implements ClockDelayModel {

    /** Default table location, relative to the RapidWright installation. */
    public static final String DEFAULT_FILE = TimingModel.TIMING_DATA_DIR
            + "/versal/clock_node_delay.txt";

    public static final String BRANCH_TERM = "BRANCHES_PASSED";

    private final Map<String, double[]> arrival = new HashMap<>();
    private final Map<String, double[]> byType = new HashMap<>();
    /** Arrival-scale fallback per wire template, from fitted instance means. */
    private final Map<String, double[]> typeFallback = new HashMap<>();
    private final int bufgMaxPs;
    private final int bufgMinPs;

    private final Map<Site, List<Node>> siteRoute = new HashMap<>();
    private final Map<Node, Integer> children = new HashMap<>();

    private long termsPriced;
    private long termsFallback;
    private long termsUnknown;
    private final boolean armed;
    private final com.xilinx.rapidwright.design.Design design;

    public VersalClockNodeModel(Net clk, Path table) throws IOException {
        this(clk, table, VersalClockTimingModel.DEFAULT_BUFG_MAX_PS,
                VersalClockTimingModel.DEFAULT_BUFG_MIN_PS);
    }

    public VersalClockNodeModel(Net clk, Path table, int bufgMaxPs, int bufgMinPs)
            throws IOException {
        this.bufgMaxPs = bufgMaxPs;
        this.bufgMinPs = bufgMinPs;
        com.xilinx.rapidwright.design.Design design = clk.getDesign();
        this.armed = design != null && hasArmedStations(design);
        this.design = design;
        read(table);
        if (arrival.isEmpty()) {
            throw new IOException("No clock_node_delay entries in " + table);
        }
        Map<Node, Node> parent = VersalClockTimingModel.buildParents(clk);
        for (Map.Entry<Node, Node> e : parent.entrySet()) {
            if (e.getValue() != null) {
                children.merge(e.getValue(), 1, Integer::sum);
            }
        }
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) {
                continue;
            }
            List<Node> route = VersalClockTimingModel.routeToSink(parent, spi.getConnectedNode());
            if (!route.isEmpty()) {
                siteRoute.putIfAbsent(spi.getSite(), route);
            }
        }
    }

    /** Builds the model for a clock net from the default table location. */
    public static VersalClockNodeModel load(Net clk) throws IOException {
        String file = FileTools.getRapidWrightResourceFileName(DEFAULT_FILE);
        if (file == null) {
            throw new IOException("Cannot locate " + DEFAULT_FILE + ": RAPIDWRIGHT_PATH is not set");
        }
        return new VersalClockNodeModel(clk, Paths.get(file));
    }

    // ------------------------------------------------------------------
    // Featurization: the single implementation, delegated to by the
    // characterization harness so fit and prediction cannot drift.
    // ------------------------------------------------------------------

    /** Spine and distribution nodes: the shared trunk above the leaves. */
    public static boolean isTrunk(Node n) {
        String ic = n.getIntentCode().toString();
        return ic.contains("VROUTE") || ic.contains("VDISTR") || ic.contains("HROUTE")
                || (ic.contains("HDISTR") && !ic.contains("LOCAL")) || ic.contains("GCLK")
                || ic.contains("BUFG");
    }

    /** The arrival term key: per instance for trunk nodes, per type for leaves. */
    public static String arrivalKey(Node n) {
        return isTrunk(n) ? n.toString() : n.getIntentCode().toString();
    }

    /** The pessimism (and fallback) term key: type by tile type. */
    public static String typeKey(Node n) {
        return n.getIntentCode() + "@" + n.getTile().getTileTypeEnum();
    }

    /** Feature name for programmed leaf deskew taps at the sink slice. */
    public static final String LEAF_TAPS_TERM = "LEAF_TAPS";
    /** Feature name for armed SSIT delay stations crossed by the route. */
    public static final String ARMED_STATION_TERM = "ARMED_STATIONS";

    /** Programmed leaf deskew taps at a slice, or 0 when the design carries no attributes. */
    public static int leafTaps(com.xilinx.rapidwright.design.Design design, Site site) {
        if (design == null || design.getBELAttrs() == null || !site.getName().startsWith("SLICE")) {
            return 0;
        }
        return com.xilinx.rapidwright.router.VersalClockDeskew.getLeafClockDelay(design, site);
    }

    /** Whether a route node passes through an SSIT programmable delay station. */
    public static boolean isDelayStationNode(Node n) {
        return n.getWireName().contains("PD_OPT_DELAY");
    }

    /**
     * Whether the design armed the SSIT stations' optimized (self-computed)
     * delay. Vivado's router arms them design-wide; an armed station carries
     * delay no route topology reveals, so it is a fitted per-crossing term.
     */
    public static boolean hasArmedStations(com.xilinx.rapidwright.design.Design design) {
        if (design == null || design.getBELAttrs() == null) {
            return false;
        }
        for (Map.Entry<com.xilinx.rapidwright.device.Site, com.xilinx.rapidwright.design.SiteConfig> e
                : design.getBELAttrs().entrySet()) {
            if (e.getKey().getSiteTypeEnum().name().contains("GCLK_DELAY")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // ClockDelayModel.
    // ------------------------------------------------------------------

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.SIGNOFF;
    }

    @Override
    public Float getArrivalPs(Site site, Corner corner) {
        List<Node> route = siteRoute.get(site);
        if (route == null) {
            return null;
        }
        int ci = corner == Corner.SLOW_MIN ? 1 : 0;
        double t = corner == Corner.SLOW_MIN ? bufgMinPs : bufgMaxPs;
        for (Node n : route) {
            double[] term = arrival.get(arrivalKey(n));
            if (term != null) {
                termsPriced++;
            } else {
                // A trunk instance the fit never saw: fall back to the mean
                // of fitted instance terms of the same wire template — an
                // arrival-scale value. The pessimism coefficients are
                // whole-route distributors and must never be summed here.
                term = typeFallback.get(n.getWireName().replaceAll("\\d+", "#"));
                if (term != null) {
                    termsFallback++;
                } else {
                    termsUnknown++;
                    continue;
                }
            }
            t += term[ci];
        }
        // Programmed state the checkpoint carries: leaf deskew taps at the
        // sink slice, and armed SSIT delay stations crossed by the route.
        {
            int taps = leafTaps(design, site);
            if (taps > 0) {
                double[] term = byType.get(LEAF_TAPS_TERM);
                t += taps * (term != null ? term[ci] : 68.0);
            }
        }
        if (armed) {
            int stations = 0;
            for (Node n : route) {
                if (isDelayStationNode(n)) {
                    stations++;
                }
            }
            double[] term = byType.get(ARMED_STATION_TERM);
            if (stations > 0 && term != null) {
                t += stations * term[ci];
            }
        }
        return (float) t;
    }

    /**
     * Pessimism removal as the corner spread of the shared route prefix —
     * no divergence-node table. Hold pessimism is reported negative, the way
     * Vivado signs it.
     */
    @Override
    public float getPessimismRemovalPs(Site launch, Site capture, boolean setup) {
        List<Node> a = siteRoute.get(launch);
        List<Node> b = siteRoute.get(capture);
        if (a == null || b == null) {
            return 0;
        }
        int n = Math.min(a.size(), b.size());
        double spread = 0;
        double[] load = byType.get(BRANCH_TERM);
        for (int i = 0; i < n && a.get(i).equals(b.get(i)); i++) {
            Node node = a.get(i);
            double[] term = byType.get(typeKey(node));
            if (term != null) {
                spread += term[0] - term[1];
            }
            int branches = children.getOrDefault(node, 0) - 1;
            if (branches > 0 && load != null) {
                spread += branches * (load[0] - load[1]);
            }
        }
        return (float) (setup ? spread : -spread);
    }

    /** Whether the model has an arrival for this site's clock pin. */
    public boolean covers(Site site) {
        return siteRoute.containsKey(site);
    }

    public int getCoveredSiteCount() {
        return siteRoute.size();
    }

    /** How much pricing used exact terms vs type fallbacks, for reporting. */
    public String getCoverageSummary() {
        long total = termsPriced + termsFallback + termsUnknown;
        return String.format("clock terms: %d exact, %d type-fallback, %d unknown (of %d)",
                termsPriced, termsFallback, termsUnknown, total);
    }

    private void read(Path table) throws IOException {
        String section = null;
        for (String line : Files.readAllLines(table, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            if (t.startsWith("clock_node_delay") || t.startsWith("clock_pessimism_delay")
                    || t.startsWith("clock_type_fallback")) {
                section = t.split("\\s+")[0];
                continue;
            }
            String[] f = t.split("\\s+");
            if (section == null || f.length < 3) {
                continue;
            }
            double[] v = new double[] { Double.parseDouble(f[1]), Double.parseDouble(f[2]) };
            if (section.equals("clock_node_delay")) {
                arrival.put(f[0], v);
            } else if (section.equals("clock_type_fallback")) {
                typeFallback.put(f[0], v);
            } else {
                byType.put(f[0], v);
            }
        }
    }
}
