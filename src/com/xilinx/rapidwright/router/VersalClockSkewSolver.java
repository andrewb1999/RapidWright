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

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Assigns per-slice clock delay taps to reduce clock skew on timing-critical
 * paths, the RapidWright equivalent of Vivado's "Leaf Clock Prog Delay Opt"
 * routing phase. Tap values are applied with {@link VersalClockDeskew}.
 *
 * <p>Delaying the clock into a slice by {@code d} shifts every path that
 * captures in that slice and every path that launches from it:
 * <pre>
 *   setup slack(a -&gt; b) becomes  S + d(b) - d(a)
 *   hold  slack(a -&gt; b) becomes  H - d(b) + d(a)
 * </pre>
 * Both are difference constraints on the per-slice delays, so a feasible
 * assignment for a given setup target is found by solving the constraint
 * system with Bellman-Ford (a negative cycle means the target is
 * unachievable), and the best achievable target by bisection on that test.
 * Working directly in integer tap units keeps the solution exact — no rounding
 * step can invalidate a constraint.
 *
 * <p>Slices are movable; other site types (BRAM, DSP, NOC, ...) have no
 * {@code FF_CLK_MOD} delay and are pinned at zero, which the solver models as
 * a bound rather than dropping the constraint.
 *
 * <p>Constraints come from a Vivado {@code report_timing} run
 * ({@link #parseTimingReport}). Only reported paths are constrained, so a path
 * that was comfortably met and not reported can in principle be degraded; run
 * with enough paths to cover the design's slack range, and re-report after
 * applying to confirm.
 */
public class VersalClockSkewSolver {

    /**
     * Nominal delay per CLK_DLY_VAL tap, in nanoseconds. Vivado's device data
     * is not public; this is the value implied by the observed ~0.2ns shift at
     * 3 taps on xcv80 (speed grade -2MHP).
     */
    public static final double DEFAULT_TAP_DELAY_NS = 0.068;

    /** One reported timing path, reduced to what the solver needs. */
    public static class PathConstraint {
        public final Site source;
        public final Site destination;
        public final double slack;
        public final boolean setup;

        public PathConstraint(Site source, Site destination, double slack, boolean setup) {
            this.source = source;
            this.destination = destination;
            this.slack = slack;
            this.setup = setup;
        }

        @Override
        public String toString() {
            return (setup ? "setup " : "hold  ") + source + " -> " + destination + " @ " + slack;
        }
    }

    /** Result of a solve: the tap assignment and the target it achieves. */
    public static class Solution {
        public final Map<Site, Integer> taps;
        public final double achievedSetupTarget;
        public final boolean feasible;

        public Solution(Map<Site, Integer> taps, double achievedSetupTarget, boolean feasible) {
            this.taps = taps;
            this.achievedSetupTarget = achievedSetupTarget;
            this.feasible = feasible;
        }
    }

    private static final String SLACK_PREFIX = "Slack (";
    private static final String SOURCE_PREFIX = "Source:";
    private static final String DESTINATION_PREFIX = "Destination:";
    // Setup paths are reported as (required time - arrival time); hold paths as
    // (arrival time - required time).
    private static final String SETUP_MARKER = "(required time - arrival time)";

    /**
     * Parses a Vivado {@code report_timing} text report into path constraints,
     * resolving each endpoint's cell to the site it is placed in. Both the
     * detailed report and the far more compact {@code -path_type summary}
     * table are accepted; the summary form is what makes covering thousands of
     * paths practical.
     *
     * @param report The report file (from {@code report_timing -setup} and/or
     *               {@code -hold}, any number of paths).
     * @param design The design the report was produced from, used to map cell
     *               names to placed sites.
     * @return The parsed constraints; paths whose endpoints cannot be resolved
     *         to placed cells are skipped.
     */
    public static List<PathConstraint> parseTimingReport(Path report, Design design)
            throws IOException {
        List<String> lines = Files.readAllLines(report, StandardCharsets.UTF_8);
        for (String line : lines) {
            if (line.trim().startsWith("Startpoint") && line.contains("Slack")) {
                return parseSummaryTable(lines, design);
            }
        }
        return parseDetailedReport(report, design);
    }

    /**
     * Parses the {@code -path_type summary} table, whose rows are
     * startpoint / endpoint / slack wrapped across up to three lines and
     * distinguished by indentation. Whether the paths are setup or hold is
     * taken from the report's recorded command line.
     */
    private static List<PathConstraint> parseSummaryTable(List<String> lines, Design design) {
        boolean setup = true;
        for (String line : lines) {
            if (line.startsWith("| Command")) {
                if (line.contains("-hold")) {
                    setup = false;
                }
                break;
            }
        }
        List<PathConstraint> constraints = new ArrayList<>();
        Site src = null;
        Site dst = null;
        boolean inTable = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (!inTable) {
                if (trimmed.startsWith("Startpoint") && line.contains("Slack")) {
                    inTable = true;
                }
                continue;
            }
            if (trimmed.isEmpty() || trimmed.startsWith("---")) {
                continue;
            }
            Double slack = tryParseDouble(trimmed);
            if (slack != null) {
                if (src != null && dst != null && !src.equals(dst)) {
                    constraints.add(new PathConstraint(src, dst, slack, setup));
                }
                src = null;
                dst = null;
            } else if (!line.startsWith(" ")) {
                src = resolveSite(trimmed, design);
                dst = null;
            } else {
                dst = resolveSite(trimmed, design);
            }
        }
        return constraints;
    }

    private static Double tryParseDouble(String s) {
        try {
            return Double.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<PathConstraint> parseDetailedReport(Path report, Design design)
            throws IOException {
        List<PathConstraint> constraints = new ArrayList<>();
        Double slack = null;
        Boolean setup = null;
        Site src = null;
        try (BufferedReader br = Files.newBufferedReader(report, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith(SLACK_PREFIX)) {
                    slack = parseSlack(trimmed);
                    setup = trimmed.contains(SETUP_MARKER);
                    src = null;
                } else if (slack != null && trimmed.startsWith(SOURCE_PREFIX)) {
                    src = resolveSite(trimmed.substring(SOURCE_PREFIX.length()).trim(), design);
                } else if (slack != null && trimmed.startsWith(DESTINATION_PREFIX)) {
                    Site dst = resolveSite(trimmed.substring(DESTINATION_PREFIX.length()).trim(),
                            design);
                    if (src != null && dst != null && !src.equals(dst)) {
                        constraints.add(new PathConstraint(src, dst, slack, setup));
                    }
                    slack = null;
                    setup = null;
                    src = null;
                }
            }
        }
        return constraints;
    }

    private static double parseSlack(String line) {
        int colon = line.indexOf(':');
        if (colon == -1) {
            return Double.NaN;
        }
        String rest = line.substring(colon + 1).trim();
        int ns = rest.indexOf("ns");
        if (ns == -1) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(rest.substring(0, ns).trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /**
     * Resolves a timing-report endpoint (a cell pin like
     * {@code path/to/reg[3]/CE}) to the site the cell is placed in, trying
     * successively shorter prefixes since pin names may be several levels deep
     * inside a site.
     */
    private static Site resolveSite(String pinName, Design design) {
        String name = pinName;
        for (int i = 0; i < 3; i++) {
            int slash = name.lastIndexOf('/');
            if (slash <= 0) {
                return null;
            }
            name = name.substring(0, slash);
            Cell cell = design.getCell(name);
            if (cell != null) {
                return cell.getSite();
            }
        }
        return null;
    }

    /**
     * Finds the tap assignment that maximizes the worst achievable setup slack
     * without degrading hold.
     *
     * <p>Hold constraints are one-sided: a path that currently meets hold may
     * give up its positive slack, but a path that already violates hold is not
     * allowed to get worse.
     *
     * @param constraints The reported paths to satisfy.
     * @param tapDelayNs  Delay per tap, in ns.
     * @param maxTaps     Largest tap value the hardware accepts.
     * @return The best feasible assignment found (only non-zero taps are
     *         included).
     */
    public static Solution solve(List<PathConstraint> constraints, double tapDelayNs, int maxTaps) {
        List<PathConstraint> setups = new ArrayList<>();
        List<PathConstraint> holds = new ArrayList<>();
        Set<Site> sites = new HashSet<>();
        for (PathConstraint c : constraints) {
            if (Double.isNaN(c.slack)) {
                continue;
            }
            sites.add(c.source);
            sites.add(c.destination);
            (c.setup ? setups : holds).add(c);
        }
        if (setups.isEmpty()) {
            return new Solution(new HashMap<>(), 0, true);
        }

        double worstSetup = Double.MAX_VALUE;
        for (PathConstraint c : setups) {
            worstSetup = Math.min(worstSetup, c.slack);
        }

        // The best conceivable outcome is the worst path gaining the full tap
        // range; bisect between "no better than today" and that bound.
        double lo = worstSetup;
        double hi = worstSetup + maxTaps * tapDelayNs;
        Map<Site, Integer> best = null;
        double bestTarget = Double.NaN;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            Map<Site, Integer> taps = feasible(setups, holds, sites, mid, tapDelayNs, maxTaps);
            if (taps != null) {
                best = taps;
                bestTarget = mid;
                lo = mid;
            } else {
                hi = mid;
            }
        }
        if (best == null) {
            return new Solution(new HashMap<>(), worstSetup, false);
        }
        Map<Site, Integer> nonZero = new HashMap<>();
        for (Entry<Site, Integer> e : best.entrySet()) {
            if (e.getValue() != 0) {
                nonZero.put(e.getKey(), e.getValue());
            }
        }
        return new Solution(nonZero, bestTarget, true);
    }

    /**
     * Tests whether every setup path can reach {@code setupTarget} without
     * degrading hold, and if so returns the assignment that does it with the
     * least delay added.
     *
     * <p>Each constraint {@code t_u - t_v <= w} is equivalently a lower bound
     * {@code t_v >= t_u - w}, so raising taps from zero by repeated relaxation
     * until nothing moves yields the pointwise-minimum feasible assignment
     * (the feasible set of a difference-constraint system is closed under
     * pointwise minimum). Minimum movement matters here because only reported
     * paths are constrained: every tap beyond what the target requires is
     * unmodelled risk to the paths that were not reported. Relaxation that
     * fails to settle means a positive cycle, i.e. the target is unachievable.
     *
     * <p>Bounds are floored to whole taps, so the integer solution is
     * conservative rather than merely rounded.
     *
     * @return A feasible tap assignment, or null if the target is unachievable.
     */
    private static Map<Site, Integer> feasible(List<PathConstraint> setups,
                                               List<PathConstraint> holds,
                                               Set<Site> sites,
                                               double setupTarget,
                                               double tapDelayNs,
                                               int maxTaps) {
        List<Site> nodes = new ArrayList<>(sites);
        Map<Site, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }
        int n = nodes.size();

        // Each entry is {u, v, w} for the constraint t_u - t_v <= w.
        List<int[]> constraints = new ArrayList<>(setups.size() + holds.size());
        for (PathConstraint c : setups) {
            // S + t_dst - t_src >= target  ->  t_src - t_dst <= S - target
            int w = (int) Math.floor((c.slack - setupTarget) / tapDelayNs);
            constraints.add(new int[] { index.get(c.source), index.get(c.destination), w });
        }
        for (PathConstraint c : holds) {
            // H - t_dst + t_src >= min(H, 0)  ->  t_dst - t_src <= max(H, 0)
            int w = (int) Math.floor(Math.max(c.slack, 0.0) / tapDelayNs);
            constraints.add(new int[] { index.get(c.destination), index.get(c.source), w });
        }

        int[] t = new int[n];
        boolean settled = false;
        for (int iter = 0; iter <= n && !settled; iter++) {
            settled = true;
            for (int[] c : constraints) {
                int lower = t[c[0]] - c[2];
                if (lower > t[c[1]]) {
                    if (lower > maxTaps) {
                        return null;
                    }
                    t[c[1]] = lower;
                    settled = false;
                }
            }
        }
        if (!settled) {
            return null; // positive cycle: target unachievable
        }

        Map<Site, Integer> taps = new HashMap<>();
        for (int i = 0; i < n; i++) {
            // Sites without a programmable leaf clock delay cannot move.
            if (t[i] != 0 && !isMovable(nodes.get(i))) {
                return null;
            }
            taps.put(nodes.get(i), t[i]);
        }
        return taps;
    }

    /** True if the site has a programmable leaf clock delay (SLICEs only). */
    public static boolean isMovable(Site site) {
        SiteTypeEnum type = site.getSiteTypeEnum();
        return type == SiteTypeEnum.SLICEL || type == SiteTypeEnum.SLICEM;
    }

    /**
     * Solves for and applies a tap assignment in one step.
     *
     * @param design The design to annotate.
     * @param clk    The global clock net the taps apply to.
     * @param report A Vivado report_timing text report of the same design.
     * @return The solution applied.
     */
    public static Solution solveAndApply(Design design, Net clk, Path report) throws IOException {
        return solveAndApply(design, clk, java.util.Collections.singletonList(report),
                DEFAULT_TAP_DELAY_NS, VersalClockDeskew.MAX_TAPS);
    }

    /**
     * Solves for and applies a tap assignment from a set of reports (typically
     * one setup and one hold report of the same design).
     */
    public static Solution solveAndApply(Design design, Net clk, List<Path> reports,
                                         double tapDelayNs, int maxTaps) throws IOException {
        List<PathConstraint> constraints = new ArrayList<>();
        for (Path report : reports) {
            constraints.addAll(parseTimingReport(report, design));
        }
        Solution s = solve(constraints, tapDelayNs, maxTaps);
        if (s.feasible && !s.taps.isEmpty()) {
            // Reports of an already-tapped design measure slack that includes
            // those taps, so a solution computed from them is a delta.
            Map<Site, Integer> total = new HashMap<>();
            for (Entry<Site, Integer> e : s.taps.entrySet()) {
                int sum = VersalClockDeskew.getLeafClockDelay(design, e.getKey()) + e.getValue();
                total.put(e.getKey(), Math.min(sum, maxTaps));
            }
            VersalClockDeskew.setLeafClockDelays(design, clk, total);
            VersalClockDeskew.enableOptimizedDelay(design, clk);
        }
        return s;
    }
}
