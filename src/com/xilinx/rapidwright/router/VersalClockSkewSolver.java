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

    /** A launch/capture site pair and the clock skew currently between them. */
    public static class SkewConstraint {
        public final Site launch;
        public final Site capture;
        public final double skewNs;

        public SkewConstraint(Site launch, Site capture, double skewNs) {
            this.launch = launch;
            this.capture = capture;
            this.skewNs = skewNs;
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

        return relax(constraints, nodes, maxTaps);
    }

    /**
     * Solves a system of difference constraints {@code t_u - t_v <= w} for the
     * pointwise-minimum non-negative solution, by raising values from zero
     * until nothing moves.
     *
     * <p>Each constraint is equivalently a lower bound {@code t_v >= t_u - w},
     * and the feasible set of such a system is closed under pointwise minimum,
     * so the fixpoint of that relaxation is the least feasible assignment.
     * Minimum movement is what we want: every tap beyond what the objective
     * requires is unmodelled risk to whatever the constraint set does not
     * cover. Failure to settle means a positive cycle — the system is
     * infeasible.
     *
     * @return The minimal feasible assignment, or null if infeasible.
     */
    private static Map<Site, Integer> relax(List<int[]> constraints, List<Site> nodes,
                                            int maxTaps) {
        int n = nodes.size();
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
            return null; // positive cycle: infeasible
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

    /**
     * Finds the tap assignment that minimizes the largest clock skew on any of
     * the given launch/capture pairs.
     *
     * <p>This is the objective to use when only clock arrival is known and data
     * path delays are not. Slack-based solving is strictly better when slacks
     * are available — it can spend skew where a path needs it — but it requires
     * timing analysis; skew is computable from the clock network alone. For a
     * design whose violations are skew-induced rather than logic-bound the two
     * coincide, which is the common case for an assembled array.
     *
     * <p>Delaying a capture site's clock raises the skew on paths ending there
     * and lowers it on paths starting there, so bounding {@code |skew|} by some
     * value gives two difference constraints per pair, and the smallest
     * achievable bound follows by bisection on the same relaxation used for
     * slack solving.
     *
     * @param pairs      Connected launch/capture site pairs and their skews.
     * @param tapDelayNs Delay per tap, in ns.
     * @param maxTaps    Largest tap value the hardware accepts.
     * @return The assignment achieving the smallest bound found;
     *         {@code achievedSetupTarget} carries that bound, in ns.
     */
    public static Solution solveSkew(List<SkewConstraint> pairs, double tapDelayNs, int maxTaps) {
        Set<Site> sites = new HashSet<>();
        double worst = 0;
        for (SkewConstraint c : pairs) {
            sites.add(c.launch);
            sites.add(c.capture);
            worst = Math.max(worst, Math.abs(c.skewNs));
        }
        if (pairs.isEmpty()) {
            return new Solution(new HashMap<>(), 0, true);
        }

        // The bound can be no worse than today's and no better than zero.
        double lo = 0;
        double hi = worst;
        Map<Site, Integer> best = null;
        double bestBound = worst;
        for (int i = 0; i < 40; i++) {
            double mid = (lo + hi) / 2;
            Map<Site, Integer> taps = feasibleSkew(pairs, sites, mid, tapDelayNs, maxTaps);
            if (taps != null) {
                best = taps;
                bestBound = mid;
                hi = mid;
            } else {
                lo = mid;
            }
        }
        if (best == null) {
            return new Solution(new HashMap<>(), worst, false);
        }
        Map<Site, Integer> nonZero = new HashMap<>();
        for (Entry<Site, Integer> e : best.entrySet()) {
            if (e.getValue() != 0) {
                nonZero.put(e.getKey(), e.getValue());
            }
        }
        return new Solution(nonZero, bestBound, true);
    }

    /**
     * Minimizes skew across all pairs rather than only the worst one.
     *
     * <p>Plain min-max stops as soon as the hardest pair cannot improve, which
     * on a real design is usually a pair pinned by a block RAM or DSP endpoint
     * that has no leaf delay to program. Every other pair is then left wherever
     * it happened to be, even when taps were available to improve it. This
     * instead solves min-max, freezes the pairs that are actually binding at
     * the bound they reached, and repeats on the rest, so each round tightens
     * everything that still has room.
     *
     * @param rounds Maximum refinement rounds; the loop also stops early once a
     *               round frees nothing.
     * @return The assignment from the final round; {@code achievedSetupTarget}
     *         carries the bound the still-improvable pairs reached.
     */
    public static Solution solveSkewLexicographic(List<SkewConstraint> pairs, double tapDelayNs,
                                                  int maxTaps, int rounds) {
        Set<Site> sites = new HashSet<>();
        for (SkewConstraint c : pairs) {
            sites.add(c.launch);
            sites.add(c.capture);
        }
        if (pairs.isEmpty()) {
            return new Solution(new HashMap<>(), 0, true);
        }

        List<SkewConstraint> active = new ArrayList<>(pairs);
        // Pairs that cannot improve further, held at the bound they reached.
        List<SkewConstraint> frozen = new ArrayList<>();
        List<Double> frozenBounds = new ArrayList<>();
        Map<Site, Integer> best = null;
        double lastBound = Double.NaN;

        for (int round = 0; round < rounds && !active.isEmpty(); round++) {
            double worst = 0;
            for (SkewConstraint c : active) {
                worst = Math.max(worst, Math.abs(c.skewNs));
            }
            double lo = 0;
            double hi = worst;
            Map<Site, Integer> roundBest = null;
            double roundBound = worst;
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                Map<Site, Integer> taps = feasibleSkew(active, frozen, frozenBounds, sites, mid,
                        tapDelayNs, maxTaps);
                if (taps != null) {
                    roundBest = taps;
                    roundBound = mid;
                    hi = mid;
                } else {
                    lo = mid;
                }
            }
            if (roundBest == null) {
                break;
            }
            best = roundBest;
            lastBound = roundBound;

            // A pair sitting at the bound is what is holding it up; hold it
            // there and let the remaining pairs tighten further.
            double epsilon = tapDelayNs / 2;
            List<SkewConstraint> stillActive = new ArrayList<>();
            for (SkewConstraint c : active) {
                double achieved = Math.abs(c.skewNs
                        + tapDelayNs * (roundBest.getOrDefault(c.capture, 0)
                                - roundBest.getOrDefault(c.launch, 0)));
                if (achieved >= roundBound - epsilon) {
                    frozen.add(c);
                    frozenBounds.add(roundBound);
                } else {
                    stillActive.add(c);
                }
            }
            if (stillActive.size() == active.size()) {
                break;
            }
            active = stillActive;
        }

        if (best == null) {
            return new Solution(new HashMap<>(), Double.NaN, false);
        }
        Map<Site, Integer> nonZero = new HashMap<>();
        for (Entry<Site, Integer> e : best.entrySet()) {
            if (e.getValue() != 0) {
                nonZero.put(e.getKey(), e.getValue());
            }
        }
        return new Solution(nonZero, lastBound, true);
    }

    /**
     * Tests whether every pair's skew can be brought within {@code boundNs}.
     *
     * @return A feasible tap assignment, or null if the bound is unreachable.
     */
    private static Map<Site, Integer> feasibleSkew(List<SkewConstraint> pairs, Set<Site> sites,
                                                   double boundNs, double tapDelayNs, int maxTaps) {
        return feasibleSkew(pairs, java.util.Collections.emptyList(),
                java.util.Collections.emptyList(), sites, boundNs, tapDelayNs, maxTaps);
    }

    /**
     * Tests whether the active pairs can reach {@code boundNs} while the frozen
     * pairs stay within the bounds they were left at.
     */
    private static Map<Site, Integer> feasibleSkew(List<SkewConstraint> pairs,
                                                   List<SkewConstraint> frozen,
                                                   List<Double> frozenBounds,
                                                   Set<Site> sites, double boundNs,
                                                   double tapDelayNs, int maxTaps) {
        List<Site> nodes = new ArrayList<>(sites);
        Map<Site, Integer> index = new HashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            index.put(nodes.get(i), i);
        }

        // Each entry is {u, v, w} for the constraint t_u - t_v <= w.
        List<int[]> constraints = new ArrayList<>((pairs.size() + frozen.size()) * 2);
        for (SkewConstraint c : pairs) {
            addSkewBound(constraints, index, c, boundNs, tapDelayNs);
        }
        for (int i = 0; i < frozen.size(); i++) {
            addSkewBound(constraints, index, frozen.get(i), frozenBounds.get(i), tapDelayNs);
        }
        return relax(constraints, nodes, maxTaps);
    }

    /**
     * Bounds one pair's skew magnitude, as the two difference constraints
     * {@code |skew + t_capture - t_launch| <= bound}.
     */
    private static void addSkewBound(List<int[]> constraints, Map<Site, Integer> index,
                                     SkewConstraint c, double boundNs, double tapDelayNs) {
        int l = index.get(c.launch);
        int cap = index.get(c.capture);
        constraints.add(new int[] { cap, l,
                (int) Math.floor((boundNs - c.skewNs) / tapDelayNs) });
        constraints.add(new int[] { l, cap,
                (int) Math.floor((boundNs + c.skewNs) / tapDelayNs) });
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
